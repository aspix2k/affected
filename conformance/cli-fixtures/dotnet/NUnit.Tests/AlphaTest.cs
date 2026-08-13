namespace NUnit.Tests;

[NUnit.Framework.TestFixture]
[NUnit.Framework.NonParallelizable]
public class AlphaTest
{
    [NUnit.Framework.Test]
    public void Passes() => NUnit.Framework.Assert.That(Alpha.AlphaValue.Get(), NUnit.Framework.Is.EqualTo(1));
}
