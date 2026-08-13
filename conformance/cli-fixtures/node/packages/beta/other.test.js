import assert from "node:assert";
import { writeFileSync } from "node:fs";
import { test } from "vitest";
import { other } from "./other.js";

test("beta other", async () => {
  await new Promise(resolve => setTimeout(resolve, 750));
  writeFileSync("beta-full.marker", "ran\n");
  assert.equal(other(), 22);
});
