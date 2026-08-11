import assert from "node:assert";
import { writeFileSync } from "node:fs";
import { test } from "vitest";
import { value } from "./beta.js";

test("beta value", () => {
  writeFileSync("beta-selected.marker", "ran\n");
  assert.equal(value(), 2);
});
