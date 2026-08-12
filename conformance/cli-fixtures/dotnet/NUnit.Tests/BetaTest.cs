namespace NUnit.Tests;

[NUnit.Framework.TestFixture]
[NUnit.Framework.NonParallelizable]
public class BetaTest
{
    [NUnit.Framework.Test]
    public void Passes() => NUnit.Framework.Assert.That(Beta.BetaValue.Get(), NUnit.Framework.Is.EqualTo(2));
}
