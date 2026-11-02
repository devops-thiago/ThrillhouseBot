import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InstallationFilter } from './InstallationFilter';

const two = [
  { id: 1, account: 'acme', repositories: 3, installationWide: true },
  { id: 2, account: 'globex', repositories: 1, installationWide: false },
];

describe('InstallationFilter', () => {
  afterEach(cleanup);

  it('stays hidden for a single installation', () => {
    const { container } = render(
      <InstallationFilter installations={[two[0]]} value={null} onChange={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('reports the picked installation, and null for all of them', async () => {
    const onChange = vi.fn();
    render(<InstallationFilter installations={two} value={null} onChange={onChange} />);

    await userEvent.selectOptions(screen.getByLabelText('Installation'), '2');
    expect(onChange).toHaveBeenLastCalledWith(2);
  });

  it('shows the current choice', () => {
    render(<InstallationFilter installations={two} value={1} onChange={vi.fn()} />);
    expect(screen.getByLabelText('Installation')).toHaveValue('1');
  });
});
