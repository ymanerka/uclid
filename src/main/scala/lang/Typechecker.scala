package uclid
package lang

import scala.collection.mutable.{Map => MutableMap}
import scala.collection.mutable.{Set => MutableSet}
import scala.collection.immutable.{Map => ImmutableMap}

class TypeSynonymFinderPass extends ReadOnlyPass[Unit]
{
  type TypeMap = MutableMap[Identifier, Type]
  type TypeSet = MutableSet[Identifier]
  var typeDeclMap : TypeMap = MutableMap.empty
  var typeSynonyms : TypeSet = MutableSet.empty
  
  override def reset () {
    typeDeclMap.clear()
    typeSynonyms.clear()
  }
  override def applyOnModule( d : TraversalDirection.T, module : Module, in : Unit, context : ScopeMap) : Unit = {
    if (d == TraversalDirection.Up) {
      validateSynonyms()
      simplifySynonyms()
      printTypeDeclMap()
    }
  }
  override def applyOnTypeDecl(d : TraversalDirection.T, typDec : UclTypeDecl, in : Unit, context : ScopeMap) : Unit = {
    if (d == TraversalDirection.Down) {
      typeDeclMap.put(typDec.id, typDec.typ)
    }
  }
  override def applyOnType( d : TraversalDirection.T, typ : Type, in : Unit, context : ScopeMap) : Unit = {
    if (d == TraversalDirection.Down) {
      typ match {
        case SynonymType(name) => typeSynonyms += name
        case _ => /* skip */
      }
    }
  }

  def validateSynonyms() {
    typeSynonyms.foreach {
      (syn) => Utils.assert(typeDeclMap.contains(syn), "Type synonym '" + syn.toString + "' used without declaration.")
    }
  }

  def simplifySynonyms() {
    var simplified = false
    do {
      simplified = false
      typeDeclMap.foreach {
        case (name, decl) => {
          decl match {
            case SynonymType(otherName) =>
                simplified = true
                typeDeclMap.put(name, typeDeclMap.get(otherName).get)
            case _ =>
                typeDeclMap.put(name, decl)
          }
        }
      }
    } while (simplified)
  }

  def printTypeDeclMap() {
    typeDeclMap.foreach {
      case (name, decl) => println (name.toString + " --> " + decl.toString)
    }
    println("synonyms: " + Utils.join(typeSynonyms.map(_.toString).toList, " "))
  }
}

class TypeSynonymFinder extends ASTAnalyzer("TypeSynonymFinder", new TypeSynonymFinderPass())  {
  override def pass = super.pass.asInstanceOf[TypeSynonymFinderPass]
  in = Some(Unit)
}

class TypeSynonymRewriterPass extends RewritePass {
  lazy val manager : PassManager = analysis.manager
  lazy val typeSynonymFinderPass = manager.pass("TypeSynonymFinder").asInstanceOf[TypeSynonymFinder].pass
  override def rewriteType(typ : Type, ctx : ScopeMap) : Option[Type] = {
    typ match {
      case SynonymType(name) => typeSynonymFinderPass.typeDeclMap.get(name)
      case _ => Some(typ)
    }
  }
}

class TypeSynonymRewriter extends ASTRewriter(
    "TypeSynonymRewriter", new TypeSynonymRewriterPass())

class TypecheckPass extends ReadOnlyPass[Unit]
{
  type Memo = MutableMap[IdGenerator.Id, Type]
  var memo : Memo = MutableMap.empty
  
  type PolymorphicOpMapKey = (IdGenerator.Id, Operator)
  type PolymorphicOpMap = MutableMap[IdGenerator.Id, Operator]
  var polyOpMap : PolymorphicOpMap = MutableMap.empty
  
  override def reset() = {
    memo.clear()
    polyOpMap.clear()
  }
  
  override def applyOnExpr(d : TraversalDirection.T, e : Expr, in : Unit, ctx : ScopeMap) : Unit = {
    typeOf(e, ctx)
  }
  
  def typeOf(e : Expr, c : ScopeMap) : Type = {
    def polyResultType(op : PolymorphicOperator, argType : Type) : Type = {
      op match {
        case LTOp() | LEOp() | GTOp() | GEOp() => new BoolType()
        case AddOp() | SubOp() | MulOp() => argType
      }
    }
    def polyToInt(op : PolymorphicOperator) : IntArgOperator = {
      op match {
        case LTOp() => IntLTOp()
        case LEOp() => IntLEOp()
        case GTOp() => IntGTOp()
        case GEOp() => IntGEOp()
        case AddOp() => IntAddOp()
        case SubOp() => IntSubOp()
        case MulOp() => IntMulOp()
      }
    }
    def polyToBV(op : PolymorphicOperator, w : Int) : BVArgOperator = {
      op match {
        case LTOp() => BVLTOp(w)
        case LEOp() => BVLEOp(w)
        case GTOp() => BVGTOp(w)
        case GEOp() => BVGEOp(w)
        case AddOp() => BVAddOp(w)
        case SubOp() => BVSubOp(w)
        case MulOp() => BVMulOp(w)
      }
    }
    def opAppType(opapp : UclOperatorApplication) : Type = {
      val argTypes = opapp.operands.map(typeOf(_, c))
      opapp.op match {
        case polyOp : PolymorphicOperator => {
          Utils.assert(argTypes.size == 2, "Operator '" + opapp.op.toString + "' must have two arguments.")
          Utils.assert(argTypes(0) == argTypes(1), "Arguments to operator '" + opapp.op.toString + "' must be of the same type.")
          Utils.assert(argTypes.forall(_.isNumeric), "Arguments to operator '" + opapp.op.toString + "' must be of a numeric type.")
          typeOf(opapp.operands(0), c) match {
            case i : IntType =>
              polyOpMap.put(polyOp.astNodeId, polyToInt(polyOp))
              polyResultType(polyOp, i)
            case bv : BitVectorType =>
              polyOpMap.put(polyOp.astNodeId, polyToBV(polyOp, bv.width))
              polyResultType(polyOp, bv)
            case _ => throw new Utils.UnimplementedException("Unknown operand type to polymorphic operator '" + opapp.op.toString + "'.")
          }
        }
        case intOp : IntArgOperator => {
          Utils.assert(argTypes.size == 2, "Operator '" + opapp.op.toString + "' must have two arguments.")
          Utils.assert(argTypes.forall(_.isInstanceOf[IntType]), "Arguments to operator '" + opapp.op.toString + "' must be of type Integer.")
          intOp match {
            case IntLTOp() | IntLEOp() | IntGTOp() | IntGEOp() => new BoolType()
            case IntAddOp() | IntSubOp() | IntMulOp() => new IntType()
          }
        }
        case bvOp : BVArgOperator => {
          Utils.assert(argTypes.size == 2, "Operator '" + opapp.op.toString + "' must have two arguments.")
          Utils.assert(argTypes.forall(_.isInstanceOf[BitVectorType]), "Arguments to operator '" + opapp.op.toString + "' must be of type BitVector.")
          typeOf(opapp.operands(0), c)
          bvOp match {
            case BVLTOp(_) | BVLEOp(_) | BVGTOp(_) | BVGEOp(_) => new BoolType()
            case BVAddOp(_) | BVSubOp(_) | BVMulOp(_) => new BitVectorType(bvOp.w)
          }
        }
        case boolOp : BooleanOperator => {
          Utils.assert(argTypes.size == 2, "Operator '" + opapp.op.toString + "' must have two arguments.")
          Utils.assert(argTypes.forall(_.isInstanceOf[BoolType]), "Arguments to operator '" + opapp.op.toString + "' must be of type Bool.")
          new BoolType()
        }
        case cmpOp : ComparisonOperator => {
          Utils.assert(argTypes.size == 2, "Operator '" + opapp.op.toString + "' must have two arguments.")
          Utils.assert(argTypes(0) == argTypes(1), "Arguments to operator '" + opapp.op.toString + "' must be of the same type.")
          new BoolType()
        }
        case tOp : TemporalOperator => new TemporalType()
        case ExtractOp(hi, lo) => {
          Utils.assert(argTypes.size == 1, "Operator '" + opapp.op.toString + "' must have one argument.")
          Utils.assert(argTypes(0).isInstanceOf[BitVectorType], "Operand to operator '" + opapp.op.toString + "' must be of type BitVector.") 
          Utils.assert(argTypes(0).asInstanceOf[BitVectorType].width > hi.value.toInt, "Operand to operator '" + opapp.op.toString + "' must have width > "  + hi.value.toString + ".") 
          Utils.assert(hi.value >= lo.value , "High-operand must be greater than or equal to low operand for operator '" + opapp.op.toString + "'.") 
          Utils.assert(hi.value.toInt >= 0, "Operand to operator '" + opapp.op.toString + "' must be non-negative.") 
          Utils.assert(lo.value.toInt >= 0, "Operand to operator '" + opapp.op.toString + "' must be non-negative.") 
          new BitVectorType(hi.value.toInt - lo.value.toInt + 1)
        }
        case ConcatOp() => {
          Utils.assert(argTypes.size == 2, "Operator '" + opapp.op.toString + "' must have two arguments.")
          Utils.assert(argTypes.forall(_.isInstanceOf[BitVectorType]), "Arguments to operator '" + opapp.op.toString + "' must be of type BitVector.")
          new BitVectorType(argTypes(0).asInstanceOf[BitVectorType].width + argTypes(1).asInstanceOf[BitVectorType].width)
        }
        case RecordSelect(field) => {
          Utils.assert(argTypes.size == 1, "Record select operator must have exactly one operand.")
          Utils.assert(argTypes(0).isInstanceOf[RecordType], "Argument to record select operator must be of type record.")
          val recordType = argTypes(0).asInstanceOf[RecordType]
          val typOption = recordType.fieldType(field)
          Utils.assert(!typOption.isEmpty, "Field '" + field.toString + "' does not exist in record.")
          return typOption.get
        }
      }
    }
    
    def arraySelectType(arrSel : UclArraySelectOperation) : Type = {
      Utils.assert(typeOf(arrSel.e, c).isInstanceOf[ArrayType], "Type error in the array operand of select operation.")
      val indTypes = arrSel.index.map(typeOf(_, c))
      val arrayType = typeOf(arrSel.e, c).asInstanceOf[ArrayType]
      Utils.assert(arrayType.inTypes == indTypes, "Array index type error.")
      return arrayType.outType
    }
    
    def arrayStoreType(arrStore : UclArrayStoreOperation) : Type = {
      Utils.assert(typeOf(arrStore.e, c).isInstanceOf[ArrayType], "Type error in the array operand of store operation.")
      val indTypes = arrStore.index.map(typeOf(_, c))
      val valueType = typeOf(arrStore.value, c)
      val arrayType = typeOf(arrStore.e, c).asInstanceOf[ArrayType]
      Utils.assert(arrayType.inTypes == indTypes, "Array index type error.")
      Utils.assert(arrayType.outType == valueType, "Array update value type error.")
      return arrayType
    }
    
    def funcAppType(fapp : UclFuncApplication) : Type = {
      Utils.assert(typeOf(fapp.e, c).isInstanceOf[MapType], "Type error in function application (not a function).")
      val funcType = typeOf(fapp.e,c ).asInstanceOf[MapType]
      val argTypes = fapp.args.map(typeOf(_, c))
      Utils.assert(funcType.inTypes == argTypes, "Type error in function application (argument type error).")
      return funcType.outType
    }
    
    def iteType(ite : UclITE) : Type = {
      Utils.assert(typeOf(ite.e, c).isBool, "Type error in ITE condition operand.")
      Utils.assert(typeOf(ite.t, c) == typeOf(ite.f, c), "ITE operand types don't match.")
      return typeOf(ite.t, c)
    }
    
    def lambdaType(lambda : UclLambda) : Type = {
      return MapType(lambda.ids.map(_._2), typeOf(lambda.e, c))
    }
    
    val cachedType = memo.get(e.astNodeId)
    if (cachedType.isEmpty) {
      val typ = e match {
        case i : Identifier => (c.typeOf(i).get)
        case b : BoolLit => new BoolType()
        case i : IntLit => new IntType()
        case bv : BitVectorLit => new BitVectorType(bv.width)
        case r : Record => throw new Utils.UnimplementedException("Need to implement anonymous record types.")
        case opapp : UclOperatorApplication => opAppType(opapp)
        case arrSel : UclArraySelectOperation => arraySelectType(arrSel)
        case arrStore : UclArrayStoreOperation => arrayStoreType(arrStore)
        case fapp : UclFuncApplication => funcAppType(fapp)
        case ite : UclITE => iteType(ite)
        case lambda : UclLambda => lambdaType(lambda)
      }
      memo.put(e.astNodeId, typ)
      return typ
    } else {
      return cachedType.get
    }
  }
}

class Typechecker extends ASTAnalyzer("Typechecker", new TypecheckPass())  {
  override def pass = super.pass.asInstanceOf[TypecheckPass]
  in = Some(Unit)
}

class PolymorphicTypeRewriterPass extends RewritePass {
  lazy val manager : PassManager = analysis.manager
  lazy val typeCheckerPass = manager.pass("Typechecker").asInstanceOf[Typechecker].pass
  override def rewriteOperator(op : Operator, ctx : ScopeMap) : Option[Operator] = {
    
    op match {
      case p : PolymorphicOperator => {
        val reifiedOp = typeCheckerPass.polyOpMap.get(p.astNodeId)
        Utils.assert(!reifiedOp.isEmpty, "No reified operator available for: " + p.toString)
        println("replacing " + p.toString + " with " + reifiedOp.toString)
        reifiedOp
      }
      case _ => Some(op)
    }
  }
}
class PolymorphicTypeRewriter extends ASTRewriter(
    "PolymorphicTypeRewriter", new PolymorphicTypeRewriterPass())
