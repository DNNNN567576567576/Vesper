package app.vesper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorTest {
    private fun run(keys: String): CalcState = keys.fold(CalcState()) { s, k -> CalcEngine.press(s, k) }
    private fun out(keys: String) = CalcEngine.shown(run(keys))

    @Test fun precedence() {
        assertEquals("14", out("2+3×4="))
        assertEquals("1", out("8÷4−1="))
        assertEquals("−5", out("−5="))
        assertEquals("−2", out("−3+1="))
    }

    @Test fun decimalsAndFloatJunk() {
        assertEquals("0.3", out("0.1+0.2="))
        assertEquals("0.5", out(".5="))
        assertEquals("1.25", out("5÷4="))
        assertEquals("0.333333333333", out("1÷3="))
        assertEquals("1.2", run("1.2.").expr)
        assertEquals("7", run("007").expr)
    }

    @Test fun percent() {
        assertEquals("0.5", out("50%="))
        assertEquals("110", out("100+10%="))
        assertEquals("90", out("100−10%="))
        assertEquals("20", out("200×10%="))
        assertEquals("", run("%").expr)
    }

    @Test fun chainingAndRepeatedOperators() {
        assertEquals("10", out("2+3=×2="))
        assertEquals("5×", run("2+3=×").expr)
        assertEquals("2×", run("2+−×").expr)
        assertEquals("7", out("3++4="))
        assertEquals("9", run("2+3=9").expr) // a digit after = starts fresh
        assertEquals("5", out("2+3+="))
    }

    @Test fun backspaceAndClear() {
        assertEquals("1", run("12⌫").expr)
        assertEquals("0", out("1⌫"))
        assertEquals("", run("12+C").expr)
        assertEquals("", run("2+3=⌫").expr)
    }

    @Test fun divideByZero() {
        assertEquals(CalcEngine.DIV0, out("5÷0="))
        assertEquals(CalcEngine.DIV0, out("1÷0.0+2="))
        assertEquals(CalcEngine.DIV0, run("5÷0=+").result)
        assertEquals("3", out("5÷0=3+0="))
    }

    @Test fun formatting() {
        assertEquals("0", CalcEngine.format(0.0))
        assertEquals("100", CalcEngine.format(100.0))
        assertEquals("1000000", CalcEngine.format(1e6))
        assertEquals("0.0001", CalcEngine.format(1e-4))
        assertEquals("−2.5", CalcEngine.format(-2.5))
        assertEquals("0.3", CalcEngine.format(0.1 + 0.2))
        assertEquals("123456789012", CalcEngine.format(123456789012.4))
        assertEquals("1", CalcEngine.format(0.9999999999999))
        assertEquals("1e20", CalcEngine.format(1e20))
    }
}
