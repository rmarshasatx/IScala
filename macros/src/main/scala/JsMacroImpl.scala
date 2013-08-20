package org.refptr.iscala.json

import play.api.libs.json.{Reads,Writes,Format}

import scala.language.reflectiveCalls
import scala.reflect.macros.Context
import language.experimental.macros

object JsMacroImpl {
    private def debug(msg: => Any) = {
        if (false) {
            println(msg.toString.split("\n").map("[macro] " + _).mkString("\n"))
        }
    }

    /* JSON writer for sealed traits.
     *
     * This macro generates code equivalent to:
     * ```
     * new Writes[T] {
     *     val $writes$T_1 = Json.writes[T_1]
     *     ...
     *     val $writes$T_n = Json.writes[T_n]
     *
     *     def writes(obj: cls) = (obj match {
     *         case o: T_1 => $writes$T_1.writes(o)
     *         ...
     *         case o: T_n => $writes$T_n.writes(o)
     *     }) ++ JsObject(List(
     *         ("field_1", Json.toJson(obj.field_1)),
     *         ...
     *         ("field_n", Json.toJson(obj.field_n))))
     * }
     * ```
     *
     * `T` is a sealed trait with case subclasses `T_1`, ... `T_n`. Fields `field_1`,
     * ..., `field_n` are `T`'s vals that don't appear in `T_i` constructors.
     *
     * Make sure that trait and subclasses, and implicit val are in separate compilation
     * units. Otherwise, due to a bug in the compiler (see SI-7048), `knownDirectSubclasses`
     * will give an empty list even if subclasses exist.
    */
    def sealedWritesImpl[A: c.WeakTypeTag](c: Context): c.Expr[Writes[A]] = {
        import c.universe._

        val tpe = weakTypeOf[A]
        val symbol = tpe.typeSymbol

        if (!symbol.isClass) {
            c.abort(c.enclosingPosition, "expected a class or trait")
        }

        val cls = symbol.asClass

        if (!cls.isTrait) {
            writesImpl(c)
        } else if (!cls.isSealed) {
            c.abort(c.enclosingPosition, "expected a sealed trait")
        } else {
            val children = cls.knownDirectSubclasses.toList

            if (children.isEmpty) {
                c.abort(c.enclosingPosition, "trait has no subclasses")
            } else if (!children.forall(_.isClass) || !children.map(_.asClass).forall(_.isCaseClass)) {
                c.abort(c.enclosingPosition, "all children must be case classes")
            } else {
                val playJson = Select(Select(Select(Ident(newTermName("play")), newTermName("api")), newTermName("libs")), newTermName("json"))
                val collImmu = Select(Select(Ident(newTermName("scala")), newTermName("collection")), newTermName("immutable"))

                val writesDefs = children.map { child =>
                    ValDef(
                        Modifiers(), newTermName("$writes$" + child.name.toString), TypeTree(),
                        TypeApply(Select(Select(playJson, newTermName("Json")), newTermName("writes")),
                                  List(Ident(child))))
                }

                val caseDefs = children.map { child =>
                    CaseDef(
                        Bind(newTermName("o"), Typed(Ident(nme.WILDCARD),
                             Ident(child))),
                        EmptyTree,
                        Apply(
                            Select(Select(This(newTypeName("$anon")), newTermName("$writes$" + child.name.toString)), newTermName("writes")),
                            List(Ident(newTermName("o")))))
                }

                val names = children.flatMap(
                    _.typeSignature
                     .declaration(nme.CONSTRUCTOR)
                     .asMethod
                     .paramss(0)
                     .map(_.name.toString)
                 ).toSet

                val fieldNames = cls.typeSignature
                   .declarations
                   .toList
                   .filter(_.isMethod)
                   .map(_.asMethod)
                   .filter(_.isStable)
                   .filter(_.isPublic)
                   .map(_.name.toString)
                   .filterNot(names contains _)

                val fieldDefs = fieldNames.map { fieldName =>
                    Apply(
                        Select(Select(Ident(newTermName("scala")), newTermName("Tuple2")), newTermName("apply")),
                        List(
                            Literal(Constant(fieldName)),
                            Apply(
                                Select(Select(playJson, newTermName("Json")), newTermName("toJson")),
                                List(Select(Ident(newTermName("obj")), newTermName(fieldName))))))
                }

                val expr = c.Expr[Writes[A]](
                    Block(
                        List(
                            ClassDef(Modifiers(Flag.FINAL), newTypeName("$anon"), List(),
                                Template(
                                    List(AppliedTypeTree(Ident(weakTypeOf[Writes[A]].typeSymbol),
                                                         List(Ident(symbol)))),
                                    emptyValDef,
                                    writesDefs ++ List(
                                        DefDef(Modifiers(), nme.CONSTRUCTOR, List(),
                                            List(List()), TypeTree(),
                                            Block(
                                                List(Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())),
                                                Literal(Constant(())))),
                                        DefDef(Modifiers(), newTermName("writes"), List(),
                                            List(List(ValDef(Modifiers(Flag.PARAM), newTermName("obj"), Ident(symbol), EmptyTree))), TypeTree(),
                                            Apply(
                                                Select(
                                                    Match(
                                                        Ident(newTermName("obj")),
                                                        caseDefs),
                                                    newTermName("$plus$plus")),
                                                List(
                                                    Apply(
                                                        Select(Select(playJson, newTermName("JsObject")), newTermName("apply")),
                                                        List(
                                                            Apply(
                                                                Select(Select(collImmu, newTermName("List")), newTermName("apply")),
                                                                fieldDefs))))))
                                    )))),
                        Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())))

                debug(show(expr))
                expr
            }
        }
    }

  def readsImpl[A: c.WeakTypeTag](c: Context): c.Expr[Reads[A]] = {
    import c.universe._
    import c.universe.Flag._

    val companioned = weakTypeOf[A].typeSymbol
    val companionSymbol = companioned.companionSymbol
    val companionType = companionSymbol.typeSignature

    val libsPkg = Select(Select(Ident(newTermName("play")), newTermName("api")), newTermName("libs"))
    val jsonPkg = Select(libsPkg, newTermName("json"))
    val functionalSyntaxPkg = Select(Select(libsPkg, newTermName("functional")), newTermName("syntax"))
    val utilPkg = Select(jsonPkg, newTermName("util"))

    val jsPathSelect = Select(jsonPkg, newTermName("JsPath"))
    val readsSelect = Select(jsonPkg, newTermName("Reads"))
    val unliftIdent = Select(functionalSyntaxPkg, newTermName("unlift"))
    val lazyHelperSelect = Select(utilPkg, newTypeName("LazyHelper"))

    companionType.declaration(stringToTermName("unapply")) match {
      case NoSymbol => c.abort(c.enclosingPosition, "No unapply function found")
      case s =>
        val unapply = s.asMethod
        val unapplyReturnTypes = unapply.returnType match {
          case TypeRef(_, _, args) =>
            args.head match {
              case t @ TypeRef(_, _, Nil) => Some(List(t))
              case t @ TypeRef(_, _, args) =>
                if (t <:< typeOf[Option[_]]) Some(List(t))
                else if (t <:< typeOf[Seq[_]]) Some(List(t))
                else if (t <:< typeOf[Set[_]]) Some(List(t))
                else if (t <:< typeOf[Map[_, _]]) Some(List(t))
                else if (t <:< typeOf[Product]) Some(args)
              case _ => None
            }
          case _ => None
        }

        //println("Unapply return type:" + unapply.returnType)

        companionType.declaration(stringToTermName("apply")) match {
          case NoSymbol => c.abort(c.enclosingPosition, "No apply function found")
          case s =>
            // searches apply method corresponding to unapply
            val applies = s.asMethod.alternatives
            val apply = applies.collectFirst {
              case (apply: MethodSymbol) if (apply.paramss.headOption.map(_.map(_.asTerm.typeSignature)) == unapplyReturnTypes) => apply
            }
            apply match {
              case Some(apply) =>
                //println("apply found:" + apply)
                val params = apply.paramss.head //verify there is a single parameter group

                val inferedImplicits = params.map(_.typeSignature).map { implType =>

                  val (isRecursive, tpe) = implType match {
                    case TypeRef(_, t, args) =>
                      // Option[_] needs special treatment because we need to use XXXOpt
                      if (implType.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                        (args.exists { a => a.typeSymbol == companioned }, args.head)
                      else (args.exists { a => a.typeSymbol == companioned }, implType)
                    case TypeRef(_, t, _) =>
                      (false, implType)
                  }

                  // builds reads implicit from expected type
                  val neededImplicitType = appliedType(weakTypeOf[Reads[_]].typeConstructor, tpe :: Nil)
                  // infers implicit
                  val neededImplicit = c.inferImplicitValue(neededImplicitType)
                  (implType, neededImplicit, isRecursive, tpe)
                }

                // if any implicit is missing, abort
                // else goes on
                inferedImplicits.collect { case (t, impl, rec, _) if (impl == EmptyTree && !rec) => t } match {
                  case List() =>
                    val namedImplicits = params.map(_.name).zip(inferedImplicits)
                    //println("Found implicits:"+namedImplicits)

                    val helperMember = Select(This(tpnme.EMPTY), newTermName("lazyStuff"))

                    var hasRec = false

                    // combines all reads into CanBuildX
                    val canBuild = namedImplicits.map {
                      case (name, (t, impl, rec, tpe)) =>
                        // inception of (__ \ name).read(impl)
                        val jspathTree = Apply(
                          Select(jsPathSelect, newTermName(scala.reflect.NameTransformer.encode("\\"))),
                          List(Literal(Constant(name.decoded)))
                        )

                        if (!rec) {
                          val readTree =
                            if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                              Apply(
                                Select(jspathTree, newTermName("readNullable")),
                                List(impl)
                              )
                            else Apply(
                              Select(jspathTree, newTermName("read")),
                              List(impl)
                            )

                          readTree
                        } else {
                          hasRec = true
                          val readTree =
                            if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                              Apply(
                                Select(jspathTree, newTermName("readNullable")),
                                List(
                                  Apply(
                                    Select(Apply(jsPathSelect, List()), newTermName("lazyRead")),
                                    List(helperMember)
                                  )
                                )
                              )

                            else {
                              Apply(
                                Select(jspathTree, newTermName("lazyRead")),
                                if (tpe.typeConstructor <:< typeOf[List[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("list")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Set[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("set")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Seq[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("seq")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Map[_, _]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("map")),
                                    List(helperMember)
                                  )
                                )
                                else List(helperMember)
                              )
                            }

                          readTree
                        }
                    }.reduceLeft { (acc, r) =>
                      Apply(
                        Select(acc, newTermName("and")),
                        List(r)
                      )
                    }

                    // builds the final Reads using apply method
                    val applyMethod =
                      Function(
                        params.foldLeft(List[ValDef]())((l, e) =>
                          l :+ ValDef(Modifiers(PARAM), newTermName(e.name.encoded), TypeTree(), EmptyTree)
                        ),
                        Apply(
                          Select(Ident(companionSymbol.name), newTermName("apply")),
                          params.foldLeft(List[Tree]())((l, e) =>
                            l :+ Ident(newTermName(e.name.encoded))
                          )
                        )
                      )

                    val unapplyMethod = Apply(
                      unliftIdent,
                      List(
                        Select(Ident(companionSymbol.name), unapply.name)
                      )
                    )

                    // if case class has one single field, needs to use inmap instead of canbuild.apply
                    val finalTree = if (params.length > 1) {
                      Apply(
                        Select(canBuild, newTermName("apply")),
                        List(applyMethod)
                      )
                    } else {
                      Apply(
                        Select(canBuild, newTermName("map")),
                        List(applyMethod)
                      )
                    }
                    //println("finalTree: "+finalTree)

                    if (!hasRec) {
                      val block = Block(
                        Import(functionalSyntaxPkg, List(ImportSelector(nme.WILDCARD, -1, null, -1))),
                        finalTree
                      )

                      //println("block:"+block)

                      /*val reif = reify(
                        /*new play.api.libs.json.util.LazyHelper[Format, A] {
                          override lazy val lazyStuff: Format[A] = null
                        }*/
                      )
                      println("RAW:"+showRaw(reif.tree, printKinds = true))*/

                      c.Expr[Reads[A]](block)
                    } else {
                      val helper = newTermName("helper")
                      val helperVal = ValDef(
                        Modifiers(),
                        helper,
                        TypeTree(weakTypeOf[play.api.libs.json.util.LazyHelper[Reads, A]]),
                        Apply(lazyHelperSelect, List(finalTree))
                      )

                      val block = Select(
                        Block(
                          Import(functionalSyntaxPkg, List(ImportSelector(nme.WILDCARD, -1, null, -1))),
                          ClassDef(
                            Modifiers(Flag.FINAL),
                            newTypeName("$anon"),
                            List(),
                            Template(
                              List(
                                AppliedTypeTree(
                                  lazyHelperSelect,
                                  List(
                                    Ident(weakTypeOf[Reads[A]].typeSymbol),
                                    Ident(weakTypeOf[A].typeSymbol)
                                  )
                                )
                              ),
                              emptyValDef,
                              List(
                                DefDef(
                                  Modifiers(),
                                  nme.CONSTRUCTOR,
                                  List(),
                                  List(List()),
                                  TypeTree(),
                                  Block(
                                    Apply(
                                      Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR),
                                      List()
                                    )
                                  )
                                ),
                                ValDef(
                                  Modifiers(Flag.OVERRIDE | Flag.LAZY),
                                  newTermName("lazyStuff"),
                                  AppliedTypeTree(Ident(weakTypeOf[Reads[A]].typeSymbol), List(TypeTree(weakTypeOf[A]))),
                                  finalTree
                                )
                              )
                            )
                          ),
                          Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
                        ),
                        newTermName("lazyStuff")
                      )

                      //println("block:"+block)

                      c.Expr[Reads[A]](block)
                    }
                  case l => c.abort(c.enclosingPosition, s"No implicit Reads for ${l.mkString(", ")} available.")
                }

              case None => c.abort(c.enclosingPosition, "No apply function found matching unapply return types")
            }

        }
    }
  }

  def writesImpl[A: c.WeakTypeTag](c: Context): c.Expr[Writes[A]] = {
    import c.universe._
    import c.universe.Flag._

    val companioned = weakTypeOf[A].typeSymbol
    val companionSymbol = companioned.companionSymbol
    val companionType = companionSymbol.typeSignature

    val libsPkg = Select(Select(Ident(newTermName("play")), newTermName("api")), newTermName("libs"))
    val jsonPkg = Select(libsPkg, newTermName("json"))
    val functionalSyntaxPkg = Select(Select(libsPkg, newTermName("functional")), newTermName("syntax"))
    val utilPkg = Select(jsonPkg, newTermName("util"))

    val jsPathSelect = Select(jsonPkg, newTermName("JsPath"))
    val writesSelect = Select(jsonPkg, newTermName("Writes"))
    val unliftIdent = Select(functionalSyntaxPkg, newTermName("unlift"))
    val lazyHelperSelect = Select(utilPkg, newTypeName("LazyHelper"))

    companionType.declaration(stringToTermName("unapply")) match {
      case NoSymbol => c.abort(c.enclosingPosition, "No unapply function found")
      case s =>
        val unapply = s.asMethod
        val unapplyReturnTypes = unapply.returnType match {
          case TypeRef(_, _, args) =>
            args.head match {
              case t @ TypeRef(_, _, Nil) => Some(List(t))
              case t @ TypeRef(_, _, args) =>
                if (t <:< typeOf[Option[_]]) Some(List(t))
                else if (t <:< typeOf[Seq[_]]) Some(List(t))
                else if (t <:< typeOf[Set[_]]) Some(List(t))
                else if (t <:< typeOf[Map[_, _]]) Some(List(t))
                else if (t <:< typeOf[Product]) Some(args)
              case _ => None
            }
          case _ => None
        }

        //println("Unapply return type:" + unapplyReturnTypes)

        companionType.declaration(stringToTermName("apply")) match {
          case NoSymbol => c.abort(c.enclosingPosition, "No apply function found")
          case s =>
            // searches apply method corresponding to unapply
            val applies = s.asMethod.alternatives
            val apply = applies.collectFirst {
              case (apply: MethodSymbol) if (apply.paramss.headOption.map(_.map(_.asTerm.typeSignature)) == unapplyReturnTypes) => apply
            }
            apply match {
              case Some(apply) =>
                //println("apply found:" + apply)
                val params = apply.paramss.head //verify there is a single parameter group

                val inferedImplicits = params.map(_.typeSignature).map { implType =>

                  val (isRecursive, tpe) = implType match {
                    case TypeRef(_, t, args) =>
                      // Option[_] needs special treatment because we need to use XXXOpt
                      if (implType.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                        (args.exists { a => a.typeSymbol == companioned }, args.head)
                      else (args.exists { a => a.typeSymbol == companioned }, implType)
                    case TypeRef(_, t, _) =>
                      (false, implType)
                  }

                  // builds reads implicit from expected type
                  val neededImplicitType = appliedType(weakTypeOf[Writes[_]].typeConstructor, tpe :: Nil)
                  // infers implicit
                  val neededImplicit = c.inferImplicitValue(neededImplicitType)
                  (implType, neededImplicit, isRecursive, tpe)
                }

                // if any implicit is missing, abort
                // else goes on
                inferedImplicits.collect { case (t, impl, rec, _) if (impl == EmptyTree && !rec) => t } match {
                  case List() =>
                    val namedImplicits = params.map(_.name).zip(inferedImplicits)
                    //println("Found implicits:"+namedImplicits)

                    val helperMember = Select(This(tpnme.EMPTY), newTermName("lazyStuff"))

                    var hasRec = false

                    // combines all reads into CanBuildX
                    val canBuild = namedImplicits.map {
                      case (name, (t, impl, rec, tpe)) =>
                        // inception of (__ \ name).read(impl)
                        val jspathTree = Apply(
                          Select(jsPathSelect, newTermName(scala.reflect.NameTransformer.encode("\\"))),
                          List(Literal(Constant(name.decoded)))
                        )

                        if (!rec) {
                          val writesTree =
                            if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                              Apply(
                                Select(jspathTree, newTermName("writeNullable")),
                                List(impl)
                              )
                            else Apply(
                              Select(jspathTree, newTermName("write")),
                              List(impl)
                            )

                          writesTree
                        } else {
                          hasRec = true
                          val writesTree =
                            if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                              Apply(
                                Select(jspathTree, newTermName("writeNullable")),
                                List(
                                  Apply(
                                    Select(Apply(jsPathSelect, List()), newTermName("lazyWrite")),
                                    List(helperMember)
                                  )
                                )
                              )

                            else {
                              Apply(
                                Select(jspathTree, newTermName("lazyWrite")),
                                if (tpe.typeConstructor <:< typeOf[List[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(writesSelect, newTermName("list")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Set[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(writesSelect, newTermName("set")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Seq[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(writesSelect, newTermName("seq")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Map[_, _]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(writesSelect, newTermName("map")),
                                    List(helperMember)
                                  )
                                )
                                else List(helperMember)
                              )
                            }

                          writesTree
                        }
                    }.reduceLeft { (acc, r) =>
                      Apply(
                        Select(acc, newTermName("and")),
                        List(r)
                      )
                    }

                    // builds the final Reads using apply method
                    //val applyMethod = Ident( companionSymbol.name )
                    val applyMethod =
                      Function(
                        params.foldLeft(List[ValDef]())((l, e) =>
                          l :+ ValDef(Modifiers(PARAM), newTermName(e.name.encoded), TypeTree(), EmptyTree)
                        ),
                        Apply(
                          Select(Ident(companionSymbol.name), newTermName("apply")),
                          params.foldLeft(List[Tree]())((l, e) =>
                            l :+ Ident(newTermName(e.name.encoded))
                          )
                        )
                      )

                    val unapplyMethod = Apply(
                      unliftIdent,
                      List(
                        Select(Ident(companionSymbol.name), unapply.name)
                      )
                    )

                    // if case class has one single field, needs to use inmap instead of canbuild.apply
                    val finalTree = if (params.length > 1) {
                      Apply(
                        Select(canBuild, newTermName("apply")),
                        List(unapplyMethod)
                      )
                    } else {
                      Apply(
                        Select(canBuild, newTermName("contramap")),
                        List(unapplyMethod)
                      )
                    }
                    //println("finalTree: "+finalTree)

                    if (!hasRec) {
                      val block = Block(
                        Import(functionalSyntaxPkg, List(ImportSelector(nme.WILDCARD, -1, null, -1))),
                        finalTree
                      )
                      //println("block:"+block)
                      c.Expr[Writes[A]](block)
                    } else {
                      val helper = newTermName("helper")
                      val helperVal = ValDef(
                        Modifiers(),
                        helper,
                        TypeTree(weakTypeOf[play.api.libs.json.util.LazyHelper[Writes, A]]),
                        Apply(lazyHelperSelect, List(finalTree))
                      )

                      val block = Select(
                        Block(
                          Import(functionalSyntaxPkg, List(ImportSelector(nme.WILDCARD, -1, null, -1))),
                          ClassDef(
                            Modifiers(Flag.FINAL),
                            newTypeName("$anon"),
                            List(),
                            Template(
                              List(
                                AppliedTypeTree(
                                  lazyHelperSelect,
                                  List(
                                    Ident(weakTypeOf[Writes[A]].typeSymbol),
                                    Ident(weakTypeOf[A].typeSymbol)
                                  )
                                )
                              ),
                              emptyValDef,
                              List(
                                DefDef(
                                  Modifiers(),
                                  nme.CONSTRUCTOR,
                                  List(),
                                  List(List()),
                                  TypeTree(),
                                  Block(
                                    Apply(
                                      Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR),
                                      List()
                                    )
                                  )
                                ),
                                ValDef(
                                  Modifiers(Flag.OVERRIDE | Flag.LAZY),
                                  newTermName("lazyStuff"),
                                  AppliedTypeTree(Ident(weakTypeOf[Writes[A]].typeSymbol), List(TypeTree(weakTypeOf[A]))),
                                  finalTree
                                )
                              )
                            )
                          ),
                          Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
                        ),
                        newTermName("lazyStuff")
                      )

                      //println("block:"+block)

                      /*val reif = reify(
                        new play.api.libs.json.util.LazyHelper[Format, A] {
                          override lazy val lazyStuff: Format[A] = null
                        }
                      )
                      //println("RAW:"+showRaw(reif.tree, printKinds = true))*/
                      c.Expr[Writes[A]](block)
                    }
                  case l => c.abort(c.enclosingPosition, s"No implicit Writes for ${l.mkString(", ")} available.")
                }

              case None => c.abort(c.enclosingPosition, "No apply function found matching unapply parameters")
            }

        }
    }
  }

  def formatImpl[A: c.WeakTypeTag](c: Context): c.Expr[Format[A]] = {
    import c.universe._
    import c.universe.Flag._

    val companioned = weakTypeOf[A].typeSymbol
    val companionSymbol = companioned.companionSymbol
    val companionType = companionSymbol.typeSignature

    val libsPkg = Select(Select(Ident(newTermName("play")), newTermName("api")), newTermName("libs"))
    val jsonPkg = Select(libsPkg, newTermName("json"))
    val functionalSyntaxPkg = Select(Select(libsPkg, newTermName("functional")), newTermName("syntax"))
    val utilPkg = Select(jsonPkg, newTermName("util"))

    val jsPathSelect = Select(jsonPkg, newTermName("JsPath"))
    val readsSelect = Select(jsonPkg, newTermName("Reads"))
    val writesSelect = Select(jsonPkg, newTermName("Writes"))
    val unliftIdent = Select(functionalSyntaxPkg, newTermName("unlift"))
    val lazyHelperSelect = Select(utilPkg, newTypeName("LazyHelper"))

    companionType.declaration(stringToTermName("unapply")) match {
      case NoSymbol => c.abort(c.enclosingPosition, "No unapply function found")
      case s =>
        val unapply = s.asMethod
        val unapplyReturnTypes = unapply.returnType match {
          case TypeRef(_, _, args) =>
            args.head match {
              case t @ TypeRef(_, _, Nil) => Some(List(t))
              case t @ TypeRef(_, _, args) =>
                if (t <:< typeOf[Option[_]]) Some(List(t))
                else if (t <:< typeOf[Seq[_]]) Some(List(t))
                else if (t <:< typeOf[Set[_]]) Some(List(t))
                else if (t <:< typeOf[Map[_, _]]) Some(List(t))
                else if (t <:< typeOf[Product]) Some(args)
              case _ => None
            }
          case _ => None
        }

        //println("Unapply return type:" + unapplyReturnTypes)

        companionType.declaration(stringToTermName("apply")) match {
          case NoSymbol => c.abort(c.enclosingPosition, "No apply function found")
          case s =>
            // searches apply method corresponding to unapply
            val applies = s.asMethod.alternatives
            val apply = applies.collectFirst {
              case (apply: MethodSymbol) if (apply.paramss.headOption.map(_.map(_.asTerm.typeSignature)) == unapplyReturnTypes) => apply
            }
            apply match {
              case Some(apply) =>
                //println("apply found:" + apply)
                val params = apply.paramss.head //verify there is a single parameter group

                val inferedImplicits = params.map(_.typeSignature).map { implType =>

                  val (isRecursive, tpe) = implType match {
                    case TypeRef(_, t, args) =>
                      // Option[_] needs special treatment because we need to use XXXOpt
                      if (implType.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                        (args.exists { a => a.typeSymbol == companioned }, args.head)
                      else (args.exists { a => a.typeSymbol == companioned }, implType)
                    case TypeRef(_, t, _) =>
                      (false, implType)
                  }

                  // builds reads implicit from expected type
                  val neededImplicitType = appliedType(weakTypeOf[Format[_]].typeConstructor, tpe :: Nil)
                  // infers implicit
                  val neededImplicit = c.inferImplicitValue(neededImplicitType)
                  (implType, neededImplicit, isRecursive, tpe)
                }

                // if any implicit is missing, abort
                // else goes on
                inferedImplicits.collect { case (t, impl, rec, _) if (impl == EmptyTree && !rec) => t } match {
                  case List() =>
                    val namedImplicits = params.map(_.name).zip(inferedImplicits)
                    //println("Found implicits:"+namedImplicits)

                    val helperMember = Select(This(tpnme.EMPTY), newTermName("lazyStuff"))

                    var hasRec = false

                    // combines all reads into CanBuildX
                    val canBuild = namedImplicits.map {
                      case (name, (t, impl, rec, tpe)) =>
                        // inception of (__ \ name).read(impl)
                        val jspathTree = Apply(
                          Select(jsPathSelect, scala.reflect.NameTransformer.encode("\\")),
                          List(Literal(Constant(name.decoded)))
                        )

                        if (!rec) {
                          val formatTree =
                            if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                              Apply(
                                Select(jspathTree, newTermName("formatNullable")),
                                List(impl)
                              )
                            else Apply(
                              Select(jspathTree, newTermName("format")),
                              List(impl)
                            )

                          formatTree
                        } else {
                          hasRec = true
                          val formatTree =
                            if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                              Apply(
                                Select(jspathTree, newTermName("formatNullable")),
                                List(
                                  Apply(
                                    Select(Apply(jsPathSelect, List()), newTermName("lazyFormat")),
                                    List(helperMember)
                                  )
                                )
                              )

                            else {
                              Apply(
                                Select(jspathTree, newTermName("lazyFormat")),
                                if (tpe.typeConstructor <:< typeOf[List[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("list")),
                                    List(helperMember)
                                  ),
                                  Apply(
                                    Select(writesSelect, newTermName("list")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Set[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("set")),
                                    List(helperMember)
                                  ),
                                  Apply(
                                    Select(writesSelect, newTermName("set")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Seq[_]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("seq")),
                                    List(helperMember)
                                  ),
                                  Apply(
                                    Select(writesSelect, newTermName("seq")),
                                    List(helperMember)
                                  )
                                )
                                else if (tpe.typeConstructor <:< typeOf[Map[_, _]].typeConstructor)
                                  List(
                                  Apply(
                                    Select(readsSelect, newTermName("map")),
                                    List(helperMember)
                                  ),
                                  Apply(
                                    Select(writesSelect, newTermName("map")),
                                    List(helperMember)
                                  )
                                )
                                else List(helperMember)
                              )
                            }

                          formatTree
                        }
                    }.reduceLeft { (acc, r) =>
                      Apply(
                        Select(acc, newTermName("and")),
                        List(r)
                      )
                    }

                    // builds the final Reads using apply method
                    //val applyMethod = Ident( companionSymbol.name )
                    val applyMethod =
                      Function(
                        params.foldLeft(List[ValDef]())((l, e) =>
                          l :+ ValDef(Modifiers(PARAM), newTermName(e.name.encoded), TypeTree(), EmptyTree)
                        ),
                        Apply(
                          Select(Ident(companionSymbol.name), newTermName("apply")),
                          params.foldLeft(List[Tree]())((l, e) =>
                            l :+ Ident(newTermName(e.name.encoded))
                          )
                        )
                      )

                    val unapplyMethod = Apply(
                      unliftIdent,
                      List(
                        Select(Ident(companionSymbol.name), unapply.name)
                      )
                    )

                    // if case class has one single field, needs to use inmap instead of canbuild.apply
                    val finalTree = if (params.length > 1) {
                      Apply(
                        Select(canBuild, newTermName("apply")),
                        List(applyMethod, unapplyMethod)
                      )
                    } else {
                      Apply(
                        Select(canBuild, newTermName("inmap")),
                        List(applyMethod, unapplyMethod)
                      )
                    }
                    //println("finalTree: "+finalTree)

                    if (!hasRec) {
                      val block = Block(
                        Import(functionalSyntaxPkg, List(ImportSelector(nme.WILDCARD, -1, null, -1))),
                        finalTree
                      )
                      //println("block:"+block)
                      c.Expr[Format[A]](block)
                    } else {
                      val helper = newTermName("helper")
                      val helperVal = ValDef(
                        Modifiers(),
                        helper,
                        Ident(weakTypeOf[play.api.libs.json.util.LazyHelper[Format, A]].typeSymbol),
                        Apply(Ident(newTermName("LazyHelper")), List(finalTree))
                      )

                      val block = Select(
                        Block(
                          Import(functionalSyntaxPkg, List(ImportSelector(nme.WILDCARD, -1, null, -1))),
                          ClassDef(
                            Modifiers(Flag.FINAL),
                            newTypeName("$anon"),
                            List(),
                            Template(
                              List(
                                AppliedTypeTree(
                                  lazyHelperSelect,
                                  List(
                                    Ident(weakTypeOf[Format[A]].typeSymbol),
                                    Ident(weakTypeOf[A].typeSymbol)
                                  )
                                )
                              ),
                              emptyValDef,
                              List(
                                DefDef(
                                  Modifiers(),
                                  nme.CONSTRUCTOR,
                                  List(),
                                  List(List()),
                                  TypeTree(),
                                  Block(
                                    Apply(
                                      Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR),
                                      List()
                                    )
                                  )
                                ),
                                ValDef(
                                  Modifiers(Flag.OVERRIDE | Flag.LAZY),
                                  newTermName("lazyStuff"),
                                  AppliedTypeTree(Ident(weakTypeOf[Format[A]].typeSymbol), List(TypeTree(weakTypeOf[A]))),
                                  finalTree
                                )
                              )
                            )
                          ),
                          Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
                        ),
                        newTermName("lazyStuff")
                      )

                      //println("block:"+block)

                      /*val reif = reify(
                        new play.api.libs.json.util.LazyHelper[Format, A] {
                          override lazy val lazyStuff: Format[A] = null
                        }
                      )
                      //println("RAW:"+showRaw(reif.tree, printKinds = true))*/
                      c.Expr[Format[A]](block)
                    }
                  case l => c.abort(c.enclosingPosition, s"No implicit format for ${l.mkString(", ")} available.")
                }

              case None => c.abort(c.enclosingPosition, "No apply function found matching unapply parameters")
            }

        }
    }
  }

}
