/**
 * Runs the inline scripts of the three installer pages against a minimal DOM stand-in and checks the
 * form they would submit. Nothing is sent anywhere.
 *
 * Usage: node --test scripts/installer/install.test.mjs
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const read = (path) => readFileSync(join(repoRoot, path), "utf8");

const PAGES = {
  offline: "install.html",
  hosted: "website/public/install.html",
  bundled: "src/main/resources/META-INF/resources/install.html",
};
const DEFAULT_ACTION = "https://github.com/settings/apps/new";
const BOT_ORIGIN = "https://bot.example.com";
const FILLED_MANIFEST = JSON.parse(read("manifest.json").replaceAll("<your-host>", "bot.example.com"));

function elementsOf(html) {
  const elements = new Map();
  for (const [tag, id] of html.matchAll(/<[a-z][^<>]*?\sid="([^"]+)"[^<>]*>/g)) {
    const listeners = [];
    elements.set(id, {
      value: "",
      textContent: "",
      disabled: /\sdisabled[\s>/]/.test(tag),
      action: /\saction="([^"]*)"/.exec(tag)?.[1],
      addEventListener: (type, listener) => listeners.push({ type, listener }),
      type(text) {
        this.value = text;
        listeners.filter((entry) => entry.type === "input").forEach((entry) => entry.listener());
      },
    });
  }
  return elements;
}

async function load(
  name,
  { manifest = FILLED_MANIFEST, manifestStatus = true, configured = "https://github.com" } = {},
) {
  const html = read(PAGES[name]);
  const elements = elementsOf(html);
  const context = vm.createContext({
    URL,
    document: { getElementById: (id) => elements.get(id) ?? null },
    window: { location: { origin: BOT_ORIGIN } },
    fetch: async (url) =>
      url === "/api/install/github"
        ? { ok: configured !== null, json: async () => ({ webUrl: configured }) }
        : { ok: manifestStatus, json: async () => manifest },
  });
  for (const [, source] of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script[^>]*>/gi)) {
    vm.runInContext(source, context, { filename: PAGES[name] });
  }
  await new Promise(setImmediate);
  const el = (id) => elements.get(id);
  return {
    el,
    address: (text) => el("github-address").type(text),
    organization: (text) => el("github-organization").type(text),
    host: (text) => el("host").type(text),
    action: () => el("manifest-form").action,
    disabled: () => el("submit-btn").disabled,
    error: () => el("github-error").textContent,
    preview: () => el("registration-target").textContent,
    manifest: () => el("manifest").value,
  };
}

async function ready(name) {
  const page = await load(name);
  if (name === "hosted") page.host("bot.example.com");
  return page;
}

function sharedBlocks(path) {
  const html = read(path);
  return {
    fields: /<div id="github-fields">[\s\S]*?<\/div>/.exec(html)?.[0],
    script: /<script id="github-target">[\s\S]*?<\/script>/.exec(html)?.[0],
  };
}

describe("installer pages", () => {
  it("share the same GitHub fields and target script", () => {
    const expected = sharedBlocks(PAGES.offline);
    assert.ok(expected.fields);
    assert.ok(expected.script);
    assert.deepEqual(sharedBlocks(PAGES.hosted), expected);
    assert.deepEqual(sharedBlocks(PAGES.bundled), expected);
  });
});

for (const name of Object.keys(PAGES)) {
  describe(`${name} installer (${PAGES[name]})`, () => {
    it("registers under the personal account on github.com when both fields are empty", async () => {
      const page = await ready(name);
      assert.equal(page.action(), DEFAULT_ACTION);
      assert.equal(page.preview(), DEFAULT_ACTION);
      assert.equal(page.disabled(), false);
      assert.equal(page.error(), "");
      assert.equal(JSON.parse(page.manifest()).public, false);
    });

    it("registers under an organization", async () => {
      const page = await ready(name);
      for (const organization of ["acme", "  Acme-2  ", "a", "a".repeat(39)]) {
        page.organization(organization);
        assert.equal(
          page.action(),
          `https://github.com/organizations/${organization.trim()}/settings/apps/new`,
        );
        assert.equal(page.disabled(), false);
      }
    });

    it("registers on another address, using only its origin", async () => {
      const page = await ready(name);
      const addresses = {
        "https://github.example.com": "https://github.example.com",
        "https://github.example.com/": "https://github.example.com",
        "  HTTPS://GitHub.Example.com:8443  ": "https://github.example.com:8443",
        "https://github.com:443": "https://github.com",
      };
      for (const [typed, origin] of Object.entries(addresses)) {
        page.address(typed);
        page.organization("");
        assert.equal(page.action(), `${origin}/settings/apps/new`, typed);
        page.organization("acme");
        assert.equal(page.action(), `${origin}/organizations/acme/settings/apps/new`, typed);
        assert.equal(page.disabled(), false, typed);
      }
    });

    it("disables the button for an address that is not a bare https origin", async () => {
      const page = await ready(name);
      const invalid = [
        "http://github.com",
        "github.com",
        "//github.com",
        "https:github.com",
        "https:////github.com",
        "https://",
        "javascript:alert(1)",
        "https://user@github.com",
        "https://user:secret@github.com",
        "https://@github.com",
        "https://github.com/enterprise",
        "https://github.com//",
        "https://github.com/.",
        "https://github.com\\enterprise",
        "https://github.com?",
        "https://github.com?a=1",
        "https://github.com#",
        "https://github.com#top",
        "https://git hub.com",
        "https://github.com:99999",
        "https://github.com%2Fenterprise",
        'https://"><img src=x>',
      ];
      for (const address of invalid) {
        page.address(address);
        assert.equal(page.disabled(), true, address);
        assert.match(page.error(), /^Enter the GitHub address as https:\/\/host/, address);
        assert.equal(page.preview(), "-", address);
      }
    });

    it("disables the button for an organization name GitHub would not accept", async () => {
      const page = await ready(name);
      const invalid = [
        "-acme",
        "acme-",
        "ac--me",
        "ac_me",
        "ac me",
        "@acme",
        "..",
        "acme/settings",
        "acme?x=1",
        "<img src=x>",
        "a".repeat(40),
      ];
      for (const organization of invalid) {
        page.organization(organization);
        assert.equal(page.disabled(), true, organization);
        assert.match(page.error(), /^Enter a valid organization name/, organization);
        assert.equal(page.preview(), "-", organization);
      }
    });

    it("enables the button again once the input is fixed", async () => {
      const page = await ready(name);
      page.address("http://github.example.com");
      page.address("https://github.example.com");
      assert.equal(page.disabled(), false);
      assert.equal(page.error(), "");
      page.address("");
      assert.equal(page.action(), DEFAULT_ACTION);
    });

    it("leaves the manifest as it was", async () => {
      const page = await ready(name);
      const before = page.manifest();
      page.address("https://github.example.com");
      page.organization("acme");
      assert.equal(page.manifest(), before);
    });
  });
}

describe("offline installer readiness", () => {
  it("stays disabled while manifest.json still has placeholders", async () => {
    const page = await load("offline", { manifest: JSON.parse(read("manifest.json")) });
    page.organization("acme");
    assert.equal(page.disabled(), true);
    assert.match(page.el("status").textContent, /^Replace <your-host>/);
  });

  it("stays disabled when manifest.json cannot be loaded", async () => {
    const page = await load("offline", { manifestStatus: false });
    page.organization("acme");
    assert.equal(page.disabled(), true);
    assert.match(page.el("status").textContent, /^Could not load manifest.json/);
  });
});

describe("hosted installer readiness", () => {
  it("stays disabled until the bot host is valid, whatever the GitHub fields say", async () => {
    const page = await load("hosted");
    page.organization("acme");
    assert.equal(page.disabled(), true);
    page.host("bot.example.com");
    assert.equal(page.disabled(), false);
    page.host("nonsense");
    page.organization("other");
    assert.equal(page.disabled(), true);
  });
});

describe("hosted installer and a Smee channel", () => {
  const channel = "https://smee.io/AbCd1234";

  it("keeps the one-time manifest code away from smee.io", async () => {
    const page = await load("hosted");
    for (const typed of [channel, `${channel}/`, "smee.io/AbCd1234", "HTTPS://SMEE.IO/AbCd1234"]) {
      page.host(typed);
      const manifest = JSON.parse(page.manifest());
      assert.equal(manifest.redirect_url, "http://localhost:8080", typed);
      assert.deepEqual(manifest.callback_urls, ["http://localhost:8080/api/auth/callback"], typed);
      assert.equal(page.disabled(), false, typed);
    }
  });

  it("still registers the webhook at the channel root", async () => {
    const page = await load("hosted");
    page.host(channel);
    assert.equal(JSON.parse(page.manifest()).hook_attributes.url, channel);
    assert.equal(page.el("preview-webhook").textContent, channel);
  });

  it("redirects to the bot's own host when the host is not a Smee channel", async () => {
    const page = await load("hosted");
    page.host("bot.example.com");
    const manifest = JSON.parse(page.manifest());
    assert.equal(manifest.redirect_url, BOT_ORIGIN);
    assert.equal(manifest.hook_attributes.url, `${BOT_ORIGIN}/api/webhook`);
  });
});

describe("bundled installer and the configured GitHub address", () => {
  const configured = "https://github.example.com";

  it("is used while the address field is empty", async () => {
    const page = await load("bundled", { configured: `${configured}/` });
    assert.equal(page.action(), `${configured}/settings/apps/new`);
    assert.equal(page.el("github-address").placeholder, configured);
    page.organization("acme");
    assert.equal(page.action(), `${configured}/organizations/acme/settings/apps/new`);
  });

  it("gives way to a typed address and comes back when the field is cleared", async () => {
    const page = await load("bundled", { configured });
    page.address("https://other.example.com");
    assert.equal(page.action(), "https://other.example.com/settings/apps/new");
    page.address("http://other.example.com");
    assert.equal(page.disabled(), true);
    page.address("");
    assert.equal(page.action(), `${configured}/settings/apps/new`);
    assert.equal(page.disabled(), false);
  });

  it("falls back to github.com when the lookup fails or answers with no web address", async () => {
    for (const answer of [null, "javascript:alert(1)"]) {
      const page = await load("bundled", { configured: answer });
      assert.equal(page.action(), DEFAULT_ACTION);
      assert.equal(page.disabled(), false);
    }
  });
});
