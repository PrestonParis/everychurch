const { createGlobPatternsForDependencies } = require('@nx/angular/tailwind');
const { join } = require('path');

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    join(__dirname, 'src/**/!(*.stories|*.spec).{ts,html}'),
    ...createGlobPatternsForDependencies(__dirname),
  ],
  theme: {
    extend: {
      colors: {
        parchment: '#FAF6EE',
        surface: '#FFFDF8',
        ink: {
          DEFAULT: '#2B2622',
          muted: '#6B6258',
        },
        oxblood: {
          DEFAULT: '#7A2331',
          dark: '#5F1B26',
        },
        gold: '#B8924A',
        hairline: '#E4DCC8',
      },
      fontFamily: {
        serif: ['Fraunces', 'ui-serif', 'Georgia', 'serif'],
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
