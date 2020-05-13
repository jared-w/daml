// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package speedy

import java.util
import scala.collection.JavaConverters._

import com.daml.lf.speedy.Speedy._
import com.daml.lf.speedy.SExpr._
import com.daml.lf.speedy.SValue._

object PrettyLightweight { // lightweight pretty printer for CEK machine states

  def ppMachine(m: Machine): String = {
    s"${ppEnv(m.env)} -- ${ppCtrl(m.ctrl)} -- ${ppKontStack(m.kontStack)}"
  }

  def ppCtrl(e: SExpr): String = {
    s"${pp(e)}"
  }

  def ppEnv(env: Env): String = {
    //s"{${commas(env.asScala.map(pp))}}"
    s"{#${env.size()}}" //show just the env size
  }

  def ppKontStack(ks: util.ArrayList[Kont]): String = {
    //ks.asScala.reverse.map(ppKont).mkString(" -- ")
    if (ks.size > 0) {
      val kTop = ks.get(ks.size -1)
      s"[#${ks.size()}]: ${ppKont(kTop)}" //show kont-stack depth + the top kont
    } else {
      s"[#${ks.size()}]" //show just the kont-stack depth
    }
  }

  def ppKont(k: Kont): String = k match {
    //case KPushTo(e) => s"KPushTo(_, ${pp(e)})"
    case e => s"KPushTo(_, ${pp(e)})"
  }

  def ppVarRef(n: Int): String = {
    s"#$n"
  }

  def pp(e: SExpr): String = e match {
    case SEValue(v) => pp(v)
    case SEVar(n) => ppVarRef(n)
    //case SEApp(func, args) => s"@(${pp(func)},${commas(args.map(pp))})"
    case SEApp(_, _) => s"@(...)"
    //case SEMakeClo(fvs, arity, body) => s"[${commas(fvs.map(ppVarRef))}]lam/$arity->${pp(body)}"
    case SEMakeClo(fvs, arity, _) => s"[${commas(fvs.map(ppVarRef))}]lam/$arity->..."
    case SEBuiltin(b) => s"${b}"
    case SEVal(_) => "<SEVal...>"
    case SELocation(_, _) => "<SELocation...>"
    case SELet(_, _) => "<SELet...>"
    case SECase(_, _) => "<SECase...>"
    case SEBuiltinRecursiveDefinition(_) => "<SEBuiltinRecursiveDefinition...>"
    case SECatch(_, _, _) => "<SECatch...>" //not seen one yet
    case SEAbs(_, _) => "<SEAbs...>" // will never get these on a running machine
    case SEImportValue(_) => "<SEImportValue...>"
    case SEWronglyTypeContractId(_, _, _) => "<SEWronglyTypeContractId...>"
    case SEArgs(args) => s"SEArgs(${commas(args.map(pp))})"
    case SEFun(prim,args) => s"SEFun(${pp(prim)},[${commas(args.asScala.map(pp))}])"
    case SEPAP(prim,args,arity) => s"SEPAP(${pp(prim)}/$arity,[${commas(args.asScala.map(pp))}])"
    case SEFinished() => "<SEFinished>"
    case SEMatch(_) => "<SEMatch...>"
    case SECacheVal(_,_) => "<SECacheVal...>"
    case SEPop(n) => s"<SEPop:$n>"
    case SELocationTrace(_) => s"<SELocationTrace>"
    case SECatchMarker(_,_,_) => s"<SECatchMarker...>"
    case SECollectArg(_,_) => s"<SECollectArg...>"
  }

  def pp(v: SValue): String = v match {
    case SInt64(n) => s"$n"
    case SPAP(prim, args, arity) =>
      s"PAP[${args.size}/$arity](${pp(prim)}(${commas(args.asScala.map(pp))})))"
    case SToken => "SToken"
    case SText(s) => s"'$s'"
    case SParty(_) => "<SParty>"
    case SStruct(_, _) => "<SStruct...>"
    case SUnit => "SUnit"
    case SList(_) => "SList"
    case _ => "<Unknown-value>" // TODO: complete cases
  }

  def pp(prim: Prim): String = prim match {
    case PBuiltin(b) => s"$b"
    case PClosure(expr, fvs) =>
      s"clo[${commas(fvs.map(pp))}]:${pp(expr)}"
  }

  def commas(xs: Seq[String]): String = xs.mkString(",")

}
