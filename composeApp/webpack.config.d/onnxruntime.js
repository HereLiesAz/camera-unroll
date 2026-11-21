// ONNX Runtime Web loads its own WebAssembly at run time from files beside the page;
// copy them out of the package into ./ort/ (see DepthModel.wasmJs.kt, which points there).
const CopyPlugin = require('copy-webpack-plugin');
const path = require('path');
const ortDist = path.dirname(require.resolve('onnxruntime-web'));
config.plugins.push(new CopyPlugin({
    patterns: [{ from: 'ort-wasm-simd-threaded.{wasm,mjs}', context: ortDist, to: 'ort/[name][ext]' }],
}));
