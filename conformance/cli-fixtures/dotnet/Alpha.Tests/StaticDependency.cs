namespace Alpha.Tests;

internal static class StaticDependency
{
    private static readonly int Value = Beta.BetaValue.Get();

    internal static void Touch() => System.GC.KeepAlive(Value);
}
