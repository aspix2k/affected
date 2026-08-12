namespace MSTest.Tests;

[Microsoft.VisualStudio.TestTools.UnitTesting.TestClass]
public class BetaTest
{
    [Microsoft.VisualStudio.TestTools.UnitTesting.TestMethod]
    public void Passes() => Microsoft.VisualStudio.TestTools.UnitTesting.Assert.AreEqual(2, Beta.BetaValue.Get());
}
