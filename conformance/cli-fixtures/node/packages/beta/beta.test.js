const assert = require("node:assert");
const test = require("node:test");
const { value } = require("./beta");

test("beta value", () => assert.equal(value(), 2));
