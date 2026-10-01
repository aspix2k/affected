import { value } from "./mod.ts";

Deno.test("alpha value", () => {
  if (value() !== 1) throw new Error("alpha value");
});
