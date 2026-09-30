const assert = require("node:assert");
const { writeFileSync } = require("node:fs");
const test = require("node:test");
const { value } = require("./gamma");

test("gamma value", () => {
  writeFileSync("gamma-selected.marker", "ran\n");
  assert.equal(value(), 3);
});
