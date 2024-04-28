/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.monitoring.runtime.instrumentation;

import java.util.HashMap;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.commons.LocalVariablesSorter;


/**
 * A <code>MethodVisitor</code> that instruments all heap allocation bytecodes
 * to record the allocation being done for profiling.
 * Instruments bytecodes that allocate heap memory to call a recording hook.
 *
 * @author Ami Fischman
 */
class AllocationMethodAdapter extends MethodVisitor {

  private final HashMap<Integer, InstRequest> instrRequests;
  private int lineno;

  /**
   * The LocalVariablesSorter used in this adapter.  Lame that it's public but
   * the ASM architecture requires setting it from the outside after this
   * AllocationMethodAdapter is fully constructed and the LocalVariablesSorter
   * constructor requires a reference to this adapter.  The only setter of
   * this should be AllocationClassAdapter.visitMethod().
   */
  public LocalVariablesSorter lvs = null;

  /**
   * A new AllocationMethodAdapter is created for each method that gets visited.
   */
  public AllocationMethodAdapter(MethodVisitor mv,
          HashMap<Integer, InstRequest> instrRequests) {
    super(Opcodes.ASM5, mv);
    this.instrRequests = instrRequests;
    this.lineno = 0;
  }

  /**
   * Used to track line numbers of the current method.
   */
  @Override
  public void visitLineNumber(int line, Label start) {
      lineno = line;
  }

  /**
   * newarray shows up as an instruction taking an int operand (the primitive
   * element type of the array) so we hook it here.
   */
  @Override
  public void visitIntInsn(int opcode, int operand) {
    if (opcode == Opcodes.NEWARRAY && instrRequests.containsKey(lineno)) {
      // instack: ... count
      // outstack: ... aref
      if (operand >= 4 && operand <= 11) {
        System.out.println("Visiting NEWARRAY at " + lineno + "; gen = " + instrRequests.get(lineno).gen());
        executeInstRequest(instrRequests.get(lineno).gen());
        super.visitIntInsn(opcode, operand);
        addGenAnnotation(TypePath.fromString("["));
      } else {
        AllocationInstrumenter.logger.severe("NEWARRAY called with an invalid operand " +
                      operand + ".  Not instrumenting this allocation!");
        super.visitIntInsn(opcode, operand);
      }
    } else {
      super.visitIntInsn(opcode, operand);
    }
  }

  /**
   * new and anewarray bytecodes take a String operand for the type of
   * the object or array element so we hook them here.  Note that new doesn't
   * actually result in any instrumentation here; we just do a bit of
   * book-keeping and do the instrumentation following the constructor call
   * (because we're not allowed to touch the object until it is initialized).
   */
  @Override
  public void visitTypeInsn(int opcode, String typeName) {
    if (opcode == Opcodes.NEW && instrRequests.containsKey(lineno)) {
      System.out.println("Visiting NEW at " + lineno + "; gen = " + instrRequests.get(lineno).gen());
      executeInstRequest(instrRequests.get(lineno).gen());
      super.visitTypeInsn(opcode, typeName);
      addGenAnnotation(TypePath.fromString("null"));
    } else if (opcode == Opcodes.ANEWARRAY && instrRequests.containsKey(lineno)) {
      System.out.println("Visiting ANEWARRAY at " + lineno + "; gen = " + instrRequests.get(lineno).gen());
      executeInstRequest(instrRequests.get(lineno).gen());
      super.visitTypeInsn(opcode, typeName);
      addGenAnnotation(TypePath.fromString("["));
    } else {
      super.visitTypeInsn(opcode, typeName);
    }

  }

  /**
   * multianewarray gets its very own visit method in the ASM framework, so we
   * hook it here.  This bytecode is different from most in that it consumes a
   * variable number of stack elements during execution.  The number of stack
   * elements consumed is specified by the dimCount operand.
   */
  @Override
  public void visitMultiANewArrayInsn(String typeName, int dimCount) {
    if (instrRequests.containsKey(lineno)) {
      System.out.println("Visiting MULTIANEWARRAY at " + lineno + "; gen = " + instrRequests.get(lineno).gen());
      executeInstRequest(instrRequests.get(lineno).gen());
      super.visitMultiANewArrayInsn(typeName, dimCount);
      addGenAnnotation(TypePath.fromString("[["));
    } else {
      super.visitMultiANewArrayInsn(typeName, dimCount);
    }
  }

  private void addGenAnnotation(TypePath tp) {
      super.visitInsnAnnotation(1140850688, tp, "Ljava/lang/Gen;", true);
  }

  private void executeInstRequest(int targetGen) {
    super.visitIntInsn(Opcodes.BIPUSH, targetGen);
    System.out.println("Pushing gen=" + targetGen);
    super.visitMethodInsn(Opcodes.INVOKESTATIC,
        "java/lang/System", "setAllocGen", "(I)V", false);
  }
}
