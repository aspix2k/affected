namespace MSTest.Tests;

[Microsoft.VisualStudio.TestTools.UnitTesting.TestClass]
public class AlphaTest
{
    [Microsoft.VisualStudio.TestTools.UnitTesting.TestMethod]
    public void Passes() => Microsoft.VisualStudio.TestTools.UnitTesting.Assert.AreEqual(1, Alpha.AlphaValue.Get());
}
