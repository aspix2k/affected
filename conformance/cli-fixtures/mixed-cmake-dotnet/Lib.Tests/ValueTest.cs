using Xunit;
using System;
using System.IO;
using System.Threading;

public class ValueTest
{
    [Fact]
    public void ReturnsOne()
    {
        var root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", ".."));
        if (File.Exists(Path.Combine(root, "mixed-fail-fast")))
        {
            File.WriteAllText(Path.Combine(root, "mixed-dotnet.pid"), $"{Environment.ProcessId}\n");
            File.WriteAllText(Path.Combine(root, "mixed-dotnet.started"), "started\n");
            Thread.Sleep(TimeSpan.FromSeconds(30));
        }
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "mixed-dotnet.marker"), "dotnet\n");
        Assert.Equal(1, Value.Get());
    }
}
