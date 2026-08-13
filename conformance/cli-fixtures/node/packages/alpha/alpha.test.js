const assert = require("node:assert");
const { writeFileSync } = require("node:fs");
const { value } = require("./alpha");

test("alpha value", () => {
  writeFileSync("alpha-selected.marker", "ran\n");
  assert.equal(value(), 1);
});
