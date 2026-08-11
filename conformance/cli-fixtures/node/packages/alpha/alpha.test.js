const assert = require("node:assert");
const test = require("node:test");
const { value } = require("./alpha");

test("alpha value", () => assert.equal(value(), 1));
