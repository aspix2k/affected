const { test } = require("node:test");
const { writeFileSync } = require("node:fs");
const assert = require("node:assert/strict");
const { value } = require("./alpha");

test("alpha value", () => {
  writeFileSync("mixed-node.marker", "ran\n");
  assert.equal(value(), 1);
});
