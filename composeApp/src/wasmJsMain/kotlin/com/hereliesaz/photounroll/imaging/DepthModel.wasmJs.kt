package com.hereliesaz.photounroll.imaging

import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

@JsFun("(n) => new Float32Array(n)")
private external fun floats(n: Int): JsAny

@JsFun("(a, i, v) => { a[i] = v; }")
private external fun put(a: JsAny, i: Int, v: Float)

@JsFun("(a, i) => a[i]")
private external fun at(a: JsAny, i: Int): Float

@JsFun("(a) => a.length")
private external fun length(a: JsAny): Int

/**
 * Runs the network with ONNX Runtime Web (single-threaded WebAssembly, which needs no special
 * page headers). The runtime's files sit in ./ort/ and the model beside the page, so nothing is
 * fetched from anyone else's server. The session is created once and reused; a failure at any
 * step rejects, and the caller falls back to color edges.
 */
@JsFun(
    """(input, width, height, modelFile, inputName) => {
  const g = globalThis;
  if (!g.__photoUnrollDepth) {
    g.__photoUnrollDepth = import('onnxruntime-web/wasm').then(async (ort) => {
      ort.env.wasm.wasmPaths = new URL('ort/', document.baseURI).href;
      ort.env.wasm.numThreads = 1;
      const model = await (await fetch(new URL(modelFile, document.baseURI))).arrayBuffer();
      const session = await ort.InferenceSession.create(model, { executionProviders: ['wasm'] });
      return { ort, session };
    });
    g.__photoUnrollDepth.catch(() => { g.__photoUnrollDepth = null; });
  }
  return g.__photoUnrollDepth.then(async ({ ort, session }) => {
    const feeds = {};
    feeds[inputName] = new ort.Tensor('float32', input, [1, 3, height, width]);
    const out = await session.run(feeds);
    return out[session.outputNames[0]].data;
  });
}""",
)
private external fun run(input: JsAny, width: Int, height: Int, modelFile: String, inputName: String): Promise<JsAny>

actual object DepthModel {
    actual suspend fun estimate(photo: RgbaImage): RgbaImage? = try {
        val input = DepthIo.input(photo)
        val array = floats(input.chw.size)
        for (i in input.chw.indices) put(array, i, input.chw[i])
        val out = run(array, input.width, input.height, DepthIo.MODEL_FILE, DepthIo.INPUT_NAME).await<JsAny>()
        DepthIo.image(FloatArray(length(out)) { at(out, it) }, input.width, input.height)
    } catch (e: Throwable) {
        null
    }
}
