const assert = require("node:assert");
const { writeFileSync } = require("node:fs");
const { other } = require("./other");

test("alpha other", async () => {
  await new Promise(resolve => setTimeout(resolve, 750));
  writeFileSync("alpha-full.marker", "ran\n");
  assert.equal(other(), 11);
});
