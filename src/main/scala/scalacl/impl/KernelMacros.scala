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
package scalacl.impl

import scala.reflect
import scalaxy.streams.Streams
import scalaxy.streams.WithMacroContext

import language.experimental.macros

import scala.reflect.macros.blackbox.Context

object KernelMacros {
  def kernelImpl(c: Context)(block: c.Expr[Unit])(contextExpr: c.Expr[scalacl.Context]): c.Expr[Unit] = {
    import c.universe._
    val vectorizer = new Vectorization with Streams with WithMacroContext with WithResult[Option[reflect.api.Universe#Expr[Unit]]] {
      override val context = c
      import context.universe._
      val result =
        vectorize(
          contextExpr.asInstanceOf[global.Expr[scalacl.Context]],
          c.typecheck(block.tree).asInstanceOf[global.Tree],
          c.freshName,
          t => c.typecheck(t.asInstanceOf[c.Tree]).asInstanceOf[global.Tree]
        // t => castTree[Tree](c.typecheck(castTree(t)))
        )
    }
    val result = vectorizer.result.asInstanceOf[Option[c.Expr[Unit]]]
    // println("RESULT = " + result)
    typeCheckOrTrace(c)("kernel = " + result) {
      result.getOrElse({
        c.error(c.enclosingPosition, "Kernel vectorization failed (only top-level foreach loops on ranges with constant positive step are supported right now)")
        c.Expr[Unit](q"()")
      })
    }
  }

  def taskImpl(c: Context)(block: c.Expr[Unit])(contextExpr: c.Expr[scalacl.Context]): c.Expr[Unit] = {
    val result = CLFunctionMacros.convertTask(c)(block)
    // println("RESULT = " + result)
    typeCheckOrTrace(c)("task = " + result) {
      c.universe.reify {
        result.splice(contextExpr.splice)
      }
    }
  }
}
