'use client';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { hydrateAgents } from '@/agents/repository';
import { useResolvedTheme } from '@/keyboard/Keyboard';
import { hydrateSettings } from '@/lib/settings';
import { ChevronLeft } from '@/ui/icons';
import css from './companion.module.css';

const NAV = [
  { href: '/agents/', label: 'Agents' },
  { href: '/models/', label: 'Models' },
  { href: '/settings/', label: 'Settings' },
  { href: '/', label: 'Keyboard' },
];

export function CompanionShell({
  title,
  back,
  active,
  children,
}: {
  title: string;
  back?: string;
  active?: string;
  children: React.ReactNode;
}) {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    hydrateSettings();
    hydrateAgents();
    setReady(true);
  }, []);
  const theme = useResolvedTheme();
  return (
    <main className={css.page} data-ak-theme={theme}>
      <div className={css.wrap}>
        <header className={css.top}>
          {back ? (
            <Link href={back} className={css.back} aria-label="Back">
              <ChevronLeft size={22} />
            </Link>
          ) : null}
          <h1 className={css.title}>{title}</h1>
        </header>
        {active ? (
          <nav className={css.tabs} aria-label="Sections">
            {NAV.map((n) => (
              <Link key={n.href} href={n.href} aria-current={active === n.href ? 'page' : undefined}>
                {n.label}
              </Link>
            ))}
          </nav>
        ) : null}
        {ready ? children : null}
      </div>
    </main>
  );
}
