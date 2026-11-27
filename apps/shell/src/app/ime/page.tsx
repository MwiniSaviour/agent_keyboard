import type { Metadata } from 'next';
import { ImeSurface } from './ImeSurface';

export const metadata: Metadata = { title: 'Keyboard' };

/** The surface loaded by the Android IME WebView at https://ak.local/ime/ (ADR-0002). */
export default function ImePage() {
  return <ImeSurface />;
}
