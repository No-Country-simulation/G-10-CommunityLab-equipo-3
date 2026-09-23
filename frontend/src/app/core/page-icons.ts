/** 3D page icons (Microsoft Fluent Emoji, MIT). Shared by the sidebar, the routes and the topbar. */
const FLUENT_3D = 'https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/';

export const PAGE_ICON = {
  summary: FLUENT_3D + 'Bar%20chart/3D/bar_chart_3d.png',
  ingest: FLUENT_3D + 'High%20voltage/3D/high_voltage_3d.png',
  content: FLUENT_3D + 'Writing%20hand/Default/3D/writing_hand_3d_default.png',
  storage: FLUENT_3D + 'Package/3D/package_3d.png',
} as const;
