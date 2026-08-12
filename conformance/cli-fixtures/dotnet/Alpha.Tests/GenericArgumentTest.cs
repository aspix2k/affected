namespace Alpha.Tests;

public class GenericArgumentTest
{
    [Xunit.Fact]
    public void Passes() => System.GC.KeepAlive(System.Array.Empty<Alpha.AlphaModel>() as object);
}
