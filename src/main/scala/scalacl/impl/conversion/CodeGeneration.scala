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
import scalacl.CLArray
import scalacl.CLFilteredArray

import scala.reflect.api.Universe
import scalaxy.streams.StreamTransforms

trait CodeGeneration extends CodeConversion with StreamTransforms {
  val global: Universe
  import global._
  import definitions._

  private[impl] def expr[T: WeakTypeTag](tree: Tree): Expr[T] = {
    import scala.reflect.api.Mirror
    import scala.reflect.api.TreeCreator
    Expr[T](rootMirror, new TreeCreator {
      def apply[U <: Universe with Singleton](m: Mirror[U]) = {
        tree.asInstanceOf[U#Tree]
      }
    })
  }

  private[impl] def ident[T](vd: ValDef): Expr[T] = expr[T](Ident(vd.name))

  private[impl] def lit[T](v: T) = expr[T](Literal(Constant(v)))

  private[impl] def newTermSymbol(name: TermName) = internal.newTermSymbol(NoSymbol, name)

  def blockToUnitFunction(block: Tree) = {
    expr[Unit => Unit](
      Function(
        List(
          ValDef(
            NoMods, TermName(fresh("noarg")), TypeTree(UnitTpe), EmptyTree)
        ),
        block
      )
    )
  }

  def freshVal(nameBase: String, tpe: Type, rhs: Tree): ValDef = {
    val name = TermName(fresh(nameBase))
    ValDef(NoMods, name, TypeTree(tpe), rhs)
  }

  def functionToFunctionKernel[A: WeakTypeTag, B: WeakTypeTag](
    captureFunction: Tree,
    kernelSalt: Long,
    outputSymbol: Symbol,
    fresh: String => String,
    typecheck: Tree => Tree): Tree = {

    def isUnit(t: Type) =
      t <:< UnitTpe || t == NoType

    val (captureParams, param, body) = typecheck(captureFunction) match {
      case Function(captureParams, Function(List(param), body)) => (captureParams, param, body)
      case Function(captureParams, Block(Nil, Function(List(param), body))) => (captureParams, param, body)
    }

    val inputTpe = param.symbol.typeSignature
    val outputTpe = body.tpe

    val bodyToConvert =
      if (isUnit(outputTpe)) {
        body
      } else {
        Assign(
          Ident(outputSymbol.name.asInstanceOf[TermName]),
          body)
      }

    val inputParamDesc: Option[ParamDesc] =
      if (isUnit(inputTpe.asInstanceOf[global.Type]))
        None
      else
        Some(
          ParamDesc(
            symbol = castSymbol(param.symbol),
            tpe = castType(inputTpe),
            output = false,
            mode = ParamKind.ImplicitArrayElement,
            usage = UsageKind.Input,
            implicitIndexDimension = Some(0)))

    val outputParamDesc: Option[ParamDesc] =
      if (isUnit(outputTpe.asInstanceOf[global.Type]))
        None
      else
        Some(
          ParamDesc(
            symbol = castSymbol(outputSymbol),
            tpe = castType(outputTpe),
            output = true,
            mode = ParamKind.ImplicitArrayElement,
            usage = UsageKind.Output,
            implicitIndexDimension = Some(0)))

    // println(s"""
    // functionToFunctionKernel:
    //   inputParamDesc: $inputParamDesc (owners ${inputParamDesc.map(_.symbol.owner)})
    //   outputParamDesc: $outputParamDesc
    //   bodyToConvert: $bodyToConvert
    // """)

    val kernel = generateFunctionKernel[A, B](
      kernelSalt = kernelSalt,
      body = castTree(bodyToConvert),
      paramDescs = inputParamDesc.toSeq ++ outputParamDesc.toSeq, // ++ captureParamDescs
      fresh = fresh,
      typecheck = typecheck
    )

    Function(captureParams, kernel.tree)
  }

  def castAnyToAnyRef(value: Tree, valueTpe: Type): Tree =
    Typed(value, TypeTree(getWrapperType(valueTpe)))

  def getWrapperType(tpe: Type): Type = tpe match {
    case _ if tpe <:< typeOf[AnyRef] => tpe
    case IntTpe => typeOf[java.lang.Integer]
    case ShortTpe => typeOf[java.lang.Short]
    case ByteTpe => typeOf[java.lang.Byte]
    case CharTpe => typeOf[java.lang.Character]
    case LongTpe => typeOf[java.lang.Long]
    case FloatTpe => typeOf[java.lang.Float]
    case DoubleTpe => typeOf[java.lang.Double]
    case BooleanTpe => typeOf[java.lang.Boolean]
    case _ => typeOf[AnyRef]
  }

  private[impl] def generateFunctionKernel[A: WeakTypeTag, B: WeakTypeTag](
    kernelSalt: Long,
    body: Tree,
    paramDescs: Seq[ParamDesc],
    fresh: String => String,
    typecheck: Tree => Tree): Expr[FunctionKernel] =
    {
      val cr @ CodeConversionResult(code, capturedInputs, capturedOutputs, capturedConstants) = convertCode(
        body,
        paramDescs,
        fresh,
        typecheck
      )

      val codeExpr = expr[String](Literal(Constant(code)))
      val kernelSaltExpr = expr[Long](Literal(Constant(kernelSalt)))

      def ident(s: global.Symbol) =
        Ident(s.asInstanceOf[Symbol].name)

      val inputs = arrayApply[CLArray[_]](
        capturedInputs
          .map(d => ident(d.symbol)).toList
      )
      val outputs = arrayApply[CLArray[_]](
        capturedOutputs
          .map(d => ident(d.symbol)).toList
      )
      val constants = arrayApply[AnyRef](
        capturedConstants
          .map(d => castAnyToAnyRef(ident(d.symbol), d.tpe)).toList
      )
      // println(s"""
      // generateFunctionKernel:
      //  code: $code
      //  paramDescs: $paramDescs,
      //  capturedInputs: $capturedInputs, 
      //  capturedOutputs: $capturedOutputs, 
      //  capturedConstants: $capturedConstants""")
      reify(
        new FunctionKernel(
          new KernelDef(
            sources = codeExpr.splice,
            salt = kernelSaltExpr.splice),
          Captures(
            inputs = inputs.splice,
            outputs = outputs.splice,
            constants = constants.splice))
      )
    }

  private def arrayApply[A: TypeTag](values: List[Tree]): Expr[Array[A]] = {
    import definitions._
    expr[Array[A]](
      Apply(
        TypeApply(
          Select(Ident(ArrayModule), TermName("apply")),
          List(TypeTree(typeOf[A]))
        ),
        values
      )
    )
  }

}
