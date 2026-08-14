using Xunit;

public class ValueTest
{
    [Fact]
    public void ReturnsOne() => Assert.Equal(1, Value.Get());
}
