import { expect, test } from "bun:test";
import { writeFileSync } from "node:fs";
import { value } from "./delta";

test("delta value", () => {
  writeFileSync("delta-selected.marker", "ran\n");
  expect(value()).toBe(4);
});
