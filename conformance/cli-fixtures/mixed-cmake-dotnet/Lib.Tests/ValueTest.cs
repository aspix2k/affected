using Xunit;
using System;
using System.IO;

public class ValueTest
{
    [Fact]
    public void ReturnsOne()
    {
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "mixed-dotnet.marker"), "dotnet\n");
        Assert.Equal(1, Value.Get());
    }
}
