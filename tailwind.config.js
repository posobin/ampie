const colors = require('tailwindcss/colors')

module.exports = {
  mode: 'jit',
  purge: [
    './build/*.html',
    './build/**/*.js'
  ],
  variants: {
    extend: {
      padding: ['first', 'last'],
      borderWidth: ['hover', 'focus'],
      textColor: ['hover'],
      borderRadius: ['first', 'last'],
      margin: ['first', 'last'],
      display: ['group-hover'],
    }
  },
  theme: {
    extend: {
      boxShadow: {
        yellow: '0 1px 14px 0 #fef08a',
        'yellow-md': '0 4px 14px 0 #fef08a',
        red: '0 1px 14px 0 #FECACA',
        'red-md': '0 4px 14px 0 #FECACA'
      },
      width: {
        'fit-content': 'fit-content'
      }
    },
    fontFamily: {
      'sans': ['"Source Sans Pro"', 'Helvetica']
    },
    fontSize: {
      'xs': ['12px', '16px'],
      'sm': ['14px', '20px'],
      'tiny': ['14px', '20px'],
      'base': ['16px', '24px'],
      'lg': ['18px', '28px'],
      'xl': ['20px', '28px'],
      '2xl': ['24px', '32px'],
      '3xl': ['30px', '36px'],
      '4xl': ['36px', '1'],
      '5xl': ['48px', '1'],
      '6xl': ['64px', '1'],
      '7xl': ['80px', '1'],
    },
    spacing: {
      '0': '0px',
      'px': '1px',
      '0.5': '2px',
      '0dot5': '2px',
      '1': '4px',
      '1.5': '6px',
      '1dot5': '6px',
      '2': '8px',
      '2.5': '10px',
      '2dot5': '10px',
      '3': '12px',
      '3.5': '14px',
      '3dot5': '14px',
      '4': '16px',
      '5': '20px',
      '6': '24px',
      '7': '28px',
      '8': '32px',
      '12': '48px',
      '14': '56px',
      '16': '64px',
    },
    colors: {
      transparent: 'transparent',
      current: 'currentColor',
      black: colors.black,
      white: colors.white,
      blue: colors.blue,
      gray: colors.coolGray,
      green: colors.green,
      indigo: colors.indigo,
      red: colors.rose,
      yellow: colors.amber,
    }
  }
}
