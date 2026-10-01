import { expect, test } from "bun:test";
import { writeFileSync } from "node:fs";

test("delta other", () => {
  writeFileSync("delta-full.marker", "ran\n");
  expect(1 + 1).toBe(2);
});
