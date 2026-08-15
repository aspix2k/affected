using Xunit;

namespace Mtp.Tests;

public sealed class BetaTests
{
    [global::Xunit.Fact]
    public void Passes()
    {
        var directory = Environment.GetEnvironmentVariable("AFFECTED_MTP_MARKERS");
        Assert.False(string.IsNullOrWhiteSpace(directory));
        Directory.CreateDirectory(directory!);
        File.WriteAllText(Path.Combine(directory!, "beta"), "ran");
        Assert.True(true);
    }
}
