namespace Alpha.Tests;

public class BetaInAlphaTest
{
    [Xunit.Fact]
    public void Passes()
    {
        SerialGate.Wait();
        Xunit.Assert.Equal(2, Beta.BetaValue.Get());
    }
}
