import type { NextConfig } from 'next';

/**
 * Static export: the same bundle is loaded by
 *  - the Capacitor companion app (webDir: out), and
 *  - the Android IME WebView via WebViewAssetLoader at https://ak.local/ime/ (ADR-0002).
 * No server features are allowed (P2: offline is the product).
 */
const nextConfig: NextConfig = {
  output: 'export',
  trailingSlash: true,
  images: { unoptimized: true },
  reactStrictMode: true,
  poweredByHeader: false,
  transpilePackages: ['@ak/protocol', '@ak/keyboard-engine'],
  allowedDevOrigins: ['*.e2b.app', '*.e2b.dev'],
};

export default nextConfig;
