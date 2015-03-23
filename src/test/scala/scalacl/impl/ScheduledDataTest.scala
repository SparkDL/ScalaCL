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

import com.nativelibs4java.opencl.CLEvent
import com.nativelibs4java.opencl.MockEvent
import com.nativelibs4java.opencl.library.OpenCLLibrary._
import com.nativelibs4java.opencl.library.IOpenCLLibrary._

import scala.collection.mutable.ArrayBuffer

class ScheduledDataTest extends BaseTest {
  behavior of "ScheduledDate"

  //TODO create higher granularization
  ignore should "perform some reads and writes" in {
    val inEvt = new MockEvent(1)
    val outEvt = new MockEvent(2)
    val opEvt = new MockEvent(3)

    val context = new Context(context = null, queue = null)

    val in = new MockScheduledData(context) {
      override def startRead(out: ArrayBuffer[CLEvent]) {
        super.startRead(out)
        out += inEvt
      }
    }
    val out = new MockScheduledData(context) {
      override def startWrite(out: ArrayBuffer[CLEvent]) {
        super.startWrite(out)
        out += outEvt
      }
    }

    context.schedule(Array(in), Array(out), events => {
      Seq(inEvt, outEvt) should equal(events.toSeq)
      opEvt
    })

    opEvt.completionCallback should not be null

    Seq(
      'startRead -> List(Nil),
      'endRead -> List(opEvt)
    ) should equal(in.calls)

    Seq(
      'startWrite -> List(List(inEvt)),
      'endWrite -> List(opEvt)
    ) should equal(out.calls)

    opEvt.completionCallback.callback(CL_COMPLETE)
    Seq(in, out).foreach {
      d => Seq('eventCompleted -> List(opEvt)) should equal(d.calls)
    }
  }
}
