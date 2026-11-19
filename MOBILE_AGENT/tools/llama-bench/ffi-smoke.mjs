import { dlopen, FFIType } from "bun:ffi";
const path = process.argv[2] || "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
console.log("dlopen:", path);
const lib = dlopen(path, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_bench: { args: [FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.ptr], returns: FFIType.i32 },
});
console.log("lb_version:", lib.symbols.lb_version());
const out = new Float64Array(7);
const model = process.argv[3];
if (model) {
  const rc = lib.symbols.lb_bench(model, 512, 24, out);
  console.log("lb_bench rc:", rc, "out:", Array.from(out));
}
