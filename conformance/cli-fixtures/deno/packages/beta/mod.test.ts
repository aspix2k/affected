import { value } from "./mod.ts";

Deno.test("beta value", () => {
  if (value() !== 2) throw new Error("beta value");
});
