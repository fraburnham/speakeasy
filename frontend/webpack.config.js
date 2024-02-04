const path = require('path')

const terser = require('terser-webpack-plugin')

module.exports = {
    entry: './src/index.js',
    output: {
        path: path.resolve(__dirname, 'dist'),
        filename: 'app.bundle.js'
    },
    optimization: {
	minimize: true,
	minimizer: [new terser()],
    }
};
