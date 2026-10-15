const { createGlobPatternsForDependencies } = require('@nx/angular/tailwind');
const { join } = require('path');

/**
 * Wraps a "--color-x: R G B" custom property (see styles.scss :root) so Tailwind's
 * opacity modifiers (bg-oxblood/10, ring-gold/50, etc. — used throughout the
 * existing templates) still work: `rgb(var(--color-x) / <alpha>)` only resolves
 * correctly when the variable holds a space-separated triplet, not a hex string,
 * and only when wrapped like this rather than passed as a bare `var(...)` value.
 */
function withOpacity(variableName) {
  return ({ opacityValue }) =>
    opacityValue === undefined
      ? `rgb(var(${variableName}))`
      : `rgb(var(${variableName}) / ${opacityValue})`;
}

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    join(__dirname, 'src/**/!(*.stories|*.spec).{ts,html}'),
    ...createGlobPatternsForDependencies(__dirname),
  ],
  theme: {
    extend: {
      // Each value reads a CSS custom property that flips to its dark value under
      // @media (prefers-color-scheme: dark) or <html data-theme="dark"> (the
      // Settings override) — see styles.scss. That's what makes every
      // bg-parchment/text-ink/bg-oxblood/etc. usage across the app switch
      // automatically, with no per-component dark: variant needed.
      colors: {
        parchment: withOpacity('--color-parchment'),
        surface: withOpacity('--color-surface'),
        ink: {
          DEFAULT: withOpacity('--color-ink'),
          muted: withOpacity('--color-ink-muted'),
        },
        oxblood: {
          DEFAULT: withOpacity('--color-oxblood'),
          dark: withOpacity('--color-oxblood-dark'),
        },
        gold: withOpacity('--color-gold'),
        hairline: withOpacity('--color-hairline'),
      },
      fontFamily: {
        serif: ['Fraunces', 'ui-serif', 'Georgia', 'serif'],
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
