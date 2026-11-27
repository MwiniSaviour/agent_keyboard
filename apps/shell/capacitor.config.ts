import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.agentkeyboard.app',
  appName: 'Agent Keyboard',
  webDir: 'out',
  server: {
    // Same origin the IME WebView uses (WebViewAssetLoader), so storage and CSP behave identically.
    androidScheme: 'https',
    hostname: 'ak.local',
  },
  android: {
    // Phase 2 adds: AgentImeService (IME), :agent foreground service, AgentKit plugin. See SPEC §9.
    webContentsDebuggingEnabled: false,
  },
};

export default config;
