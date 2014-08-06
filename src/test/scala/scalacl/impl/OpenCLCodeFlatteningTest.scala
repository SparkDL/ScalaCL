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

class OpenCLCodeFlatteningTest
    extends BaseTest
    with CodeFlatteningTest {
  import global._

  behavior of "OpenCLCodeFlattening"

  ignore should "flatten tuple of values" in {
    val x = 10
    assertEquals(
      flatCode(values = List(
        reify { x },
        reify { x + 1 }
      )),
      flatExpression(
        reify { (x, x + 1) },
        inputSymbols(reify { x })
      )
    )
  }

  ignore should "flatten tuple of references" in {
    val p = (10, 12)
    val Seq(p$1, p$2, pp$1, pp$2) = 1 to 4
    assertEquals(
      flatCode(
        statements = List(
          reify { val pp$1 = p$1 },
          reify { val pp$2 = p$2 }
        ),
        values = List(
          reify { pp$1 },
          reify { pp$2 }
        )
      ),
      flatExpression(
        reify { val pp = p; pp },
        inputSymbols(reify { p })
      )
    )
  }

  private def assertEquals(a: FlatCode[Tree], b: FlatCode[Tree]) {
    a.transform(_.toList).toString should equal(b.transform(_.toList).toString)
  }
}
