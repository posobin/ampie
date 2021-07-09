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
