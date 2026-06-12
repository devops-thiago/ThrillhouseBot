import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'ThrillhouseBot Dashboard',
  description: 'PR Review Bot — Session Monitor & Cost Analytics',
  icons: { icon: '/dashboard/favicon.png' },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
