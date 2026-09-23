const colors = require('tailwindcss/colors');

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Warm "clay" palette: amber as the brand color, stone (espresso) as the neutral.
        // Remapped here so every existing blue-/slate- utility picks up the new look.
        blue: colors.amber,
        slate: colors.stone,
        sky: colors.orange,
        cyan: colors.orange,
        espresso: {
          DEFAULT: '#1c1917',
          card: '#292524',
          muted: '#44403c',
        },
        canvas: '#f6f3ee',
        sidebar: '#ede8df',
        clay: {
          border: '#e8e2d8',
          subtle: '#ebe5dc',
          pill: '#f3efe6',
          card: '#faf8f5',
        },
      },
      fontFamily: {
        // Text and UI
        sans: ['"Plus Jakarta Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        // Brand, page/section titles and big figures
        display: ['Outfit', '"Plus Jakarta Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        // JSON, object names, IDs
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Consolas', 'monospace'],
      },
      spacing: {
        68: '17rem',
      },
      borderRadius: {
        clay: '32px',
      },
      boxShadow: {
        clay: '0 14px 30px -8px rgb(180 83 9 / 0.08), 0 4px 12px rgb(28 25 23 / 0.03)',
        'clay-hover': '0 18px 36px -6px rgb(180 83 9 / 0.14), 0 6px 14px rgb(28 25 23 / 0.04)',
        'clay-inset': 'inset -2px -2px 6px rgb(255 255 255 / 0.8), inset 2px 2px 6px rgb(28 25 23 / 0.05)',
        btn: '0 8px 18px rgb(217 119 6 / 0.32)',
      },
    },
  },
  plugins: [],
};
