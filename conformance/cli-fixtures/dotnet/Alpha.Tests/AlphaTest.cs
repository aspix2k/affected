namespace Alpha.Tests;

public class AlphaTest
{
    [Xunit.Fact]
    public void Passes()
    {
        SerialGate.Wait();
        StaticDependency.Touch();
        Xunit.Assert.Equal(1, Alpha.AlphaValue.Get());
    }
}
