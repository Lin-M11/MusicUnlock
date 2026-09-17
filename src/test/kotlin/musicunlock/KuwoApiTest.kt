package musicunlock

import musicunlock.kuwo.KuwoApi
import org.junit.Assert.assertEquals
import org.junit.Test

class KuwoApiTest {
    @Test
    fun `Secret 头与网页算法保持一致`() {
        val actual = KuwoApi.antiBotSecret(
            text = "3MiWHX6n8Zr8sN48sF3dccyTWjZ54Hxy",
            password = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324",
            random = 12_345_678L,
        )
        assertEquals(
            "101de2f5597f5e45e67fd2a938f485157b89c2b35499e5535eea21acb2ce1df500bc614e",
            actual,
        )
    }
}
