/*
 * ScalaCL - putting Scala on the GPU with JavaCL / OpenCL
 * http://scalacl.googlecode.com/
 *
 * Copyright (c) 2009-2013, Olivier Chafik (http://ochafik.com/)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Olivier Chafik nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY OLIVIER CHAFIK AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package scalacl
package impl

import scalaxy.components._

import org.junit._
import Assert._
import org.hamcrest.CoreMatchers._

class OpenCLConverterTest
    extends OpenCLConverter
    with WithRuntimeUniverse
    with WithTestFresh {
  import global._

  def conv(x: Expr[_]): FlatCode[String] = {
    //convert(typeCheck(x))
    flattenAndConvert(typeCheck(x))
  }

  def code(statements: Seq[String], values: Seq[String]): FlatCode[String] =
    FlatCode[String](statements = statements, values = values)

  @Test
  def testSimpleTuple() {
    assertEquals(
      code(
        Seq("const int x = 10;"),
        Seq("x", "(x * 2)")
      ),
      conv(reify {
        val x = 10
        (x, x * 2)
      })
    )
  }

  @Test
  def testSimpleMath() {
    import scala.math._
    assertEquals(
      code(
        Seq(),
        Seq("cos((float)10.0)")
      ),
      conv(reify {
        cos(10)
      })
    )
  }
}
