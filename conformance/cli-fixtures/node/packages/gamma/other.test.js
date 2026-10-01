const assert = require("node:assert");
const { writeFileSync } = require("node:fs");
const test = require("node:test");

test("gamma other", () => {
  writeFileSync("gamma-full.marker", "ran\n");
  assert.equal(1 + 1, 2);
});
