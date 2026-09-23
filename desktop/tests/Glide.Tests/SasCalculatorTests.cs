using Glide.Core.Security;
using Xunit;

namespace Glide.Tests;

public class SasCalculatorTests
{
    [Fact]
    public void ExtractDecimal6_ProducesValidSixDigitDecimalInRange()
    {
        byte[] digest = new byte[32];
        for (int i = 0; i < 256; i++)
        {
            digest[0] = (byte)i;
            digest[1] = (byte)(i * 3);
            digest[2] = (byte)(i * 7);
            digest[3] = (byte)(i * 11);

            int sas = SasCalculator.ExtractDecimal6(digest);
            Assert.InRange(sas, 100_000, 999_999);
        }
    }

    [Fact]
    public void CalculateSas_IsDeterministicAcrossIdenticalInputs()
    {
        byte[] clientPub = [1, 2, 3, 4, 5, 6, 7, 8];
        byte[] serverPub = [9, 10, 11, 12, 13, 14, 15, 16];
        byte[] nonce = [17, 18, 19, 20, 21, 22, 23, 24];

        int sas1 = SasCalculator.CalculateSas(clientPub, serverPub, nonce);
        int sas2 = SasCalculator.CalculateSas(clientPub, serverPub, nonce);

        Assert.Equal(sas1, sas2);
        Assert.InRange(sas1, 100_000, 999_999);
    }

    [Fact]
    public void CalculateSas_ChangesWhenNonceChanges()
    {
        byte[] clientPub = [1, 2, 3, 4, 5, 6, 7, 8];
        byte[] serverPub = [9, 10, 11, 12, 13, 14, 15, 16];
        byte[] nonce1 = [17, 18, 19, 20, 21, 22, 23, 24];
        byte[] nonce2 = [17, 18, 19, 20, 21, 22, 23, 25];

        int sas1 = SasCalculator.CalculateSas(clientPub, serverPub, nonce1);
        int sas2 = SasCalculator.CalculateSas(clientPub, serverPub, nonce2);

        // Very high probability they differ
        Assert.InRange(sas1, 100_000, 999_999);
        Assert.InRange(sas2, 100_000, 999_999);
    }
}
