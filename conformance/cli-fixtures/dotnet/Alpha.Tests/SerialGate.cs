namespace Alpha.Tests;

public static class SerialGate
{
    private static readonly object Gate = new();

    public static void Wait()
    {
        lock (Gate)
        {
            System.Threading.Thread.Sleep(400);
        }
    }
}
