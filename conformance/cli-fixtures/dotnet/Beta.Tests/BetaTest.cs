namespace Beta.Tests;

public class BetaTest
{
    [Xunit.Fact]
    public void Passes() => Xunit.Assert.Equal(2, Beta.BetaValue.Get());
}
