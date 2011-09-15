/*
 * Copyright 2011-2011 Gregory Shrago
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
package org.intellij.grammar.generator;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringHash;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashSet;
import org.intellij.grammar.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.intellij.grammar.generator.ParserGeneratorUtil.*;
import static org.intellij.grammar.generator.RuleGraphHelper.Cardinality.*;
import static org.intellij.grammar.psi.BnfTypes.*;


/**
 * @author gregory
 *         Date: 16.07.11 10:41
 */
/*
Known attributes:
parserClass - inheritable, with splitting support

<token> = <token-text>
extends
implements

mixin (rule)
pin (rule)
recoverUntil (rule)

 */

/*
 * todo ?? Grammar preprocessing: Left recursion -> suffix
 * todo ?? include grammars support grammar PSI inheritance (SQL hierarchy)
 * todo improve error reporting: investigate { atttr= attr=value } case
 */
public class ParserGenerator {
  public static final Logger LOG = Logger.getInstance("ParserGenerator");

  public static final String IELEMENTTYPE_CLASS = "com.intellij.psi.tree.IElementType";
  public static final String PSI_ELEMENT_CLASS = "com.intellij.psi.PsiElement";
  private static final String DEFAULT_FILE_HEADER = "// This is a generated file. Not intended for manual editing.";

  private final Map<String, BnfRule> ruleMap = new TreeMap<String, BnfRule>();
  private final Map<String, String> ruleParserClasses = new TreeMap<String, String>();
  private final Set<String> simpleTokens = new TreeSet<String>();
  private final Set<BnfRule> rulesWithIheritance = new HashSet<BnfRule>();
  private final MultiMap<String, String> rulesExtendsMap = new MultiMap<String, String>() {
    @Override
    protected Collection<String> createCollection() {
      return new LinkedHashSet<String>();
    }
  };
  private final BnfFile treeRoot;
  private String rootPath;
  private final String grammarRoot;
  private final boolean generateMemoizationCode;

  private int offset;
  private PrintWriter out;

  public ParserGenerator(BnfFile tree, String path) {
    treeRoot = tree;
    rootPath = path;
    final List<BnfRule> rules = tree.getRules();
    grammarRoot = rules.isEmpty() ? null : rules.get(0).getName();
    for (BnfRule r : rules) {
      ruleMap.put(r.getName(), r);
      ruleParserClasses.put(r.getName(), getAttribute(r, "parserClass", "generated.Parser"));
    }
    generateMemoizationCode = getRootAttribute(treeRoot, "memoization", false);
    computeInheritance();
  }

  public void out(String s) {
    int length = s.length();
    if (length == 0) {
      out.println();
      return;
    }
    for (int start = 0, end; start < length; start = end + 1) {
      end = StringUtil.indexOf(s, '\n', start, length);
      if (end == -1) end = length;
      String substring = s.substring(start, end);
      if (substring.startsWith("}")) offset--;
      if (offset > 0) {
        out.print(StringUtil.repeat("  ", start == 0 ? offset : offset + 1));
      }
      if (substring.endsWith("{")) offset++;
      out.println(substring);
    }
  }

  private void newLine() {
    out("");
  }

  public void generate() throws IOException {
    {
      generateParser();
    }
    boolean generatePsi = getRootAttribute(treeRoot, "generatePsi", true);
    {
      String className = getRootAttribute(treeRoot, "elementTypeHolderClass", "generated.ParserTypes");
      File parserFile = new File(rootPath + File.separatorChar + className.replace('.', File.separatorChar) + ".java");
      parserFile.getParentFile().mkdirs();
      out = new PrintWriter(new FileOutputStream(parserFile));
      try {
        generateElementTypesHolder(className, generatePsi);
      }
      finally {
        out.close();
      }
    }
    if (generatePsi) {
      Map<String, String> infClasses = new HashMap<String, String>();
      RuleGraphHelper graphHelper = new RuleGraphHelper(ruleMap);
      for (String ruleName : ruleMap.keySet()) {
        BnfRule rule = ruleMap.get(ruleName);
        if (Rule.isPrivate(rule) || Rule.isExternal(rule)) continue;
        String psiPackage = getRootAttribute(treeRoot, "psiPackage", "generated.psi");
        String psiClass = psiPackage + "." + getRulePsiClassName(rule, ruleName, true);
        infClasses.put(ruleName, psiClass);
        File psiFile = new File(rootPath + File.separatorChar + psiClass.replace('.', File.separatorChar) + ".java");
        psiFile.getParentFile().mkdirs();
        out = new PrintWriter(new FileOutputStream(psiFile));
        try {
          generatePsiIntf(graphHelper, rule, psiClass, getSuperInterfaceNames(rule, psiPackage));
        }
        finally {
          out.close();
        }
      }
      for (String ruleName : ruleMap.keySet()) {
        BnfRule rule = ruleMap.get(ruleName);
        if (Rule.isPrivate(rule) || Rule.isExternal(rule)) continue;
        String psiPackage = getRootAttribute(treeRoot, "psiImplPackage", "generated.psi.impl");
        String suffix = getRootAttribute(treeRoot, "psiImplClassSuffix", "Impl");
        String psiClass = psiPackage + "." + getRulePsiClassName(rule, ruleName, true) + suffix;
        File psiFile = new File(rootPath + File.separatorChar + psiClass.replace('.', File.separatorChar) + ".java");
        psiFile.getParentFile().mkdirs();
        out = new PrintWriter(new FileOutputStream(psiFile));
        try {
          generatePsiImpl(graphHelper, rule, psiClass, infClasses.get(ruleName), getSuperClassName(rule, psiPackage, suffix));
        }
        finally {
          out.close();
        }
      }
    }
  }

  public void generateParser() throws FileNotFoundException {
    for (String className : new TreeSet<String>(ruleParserClasses.values())) {
      Map<String, BnfRule> map = new TreeMap<String, BnfRule>();
      for (String ruleName : ruleMap.keySet()) {
        if (className.equals(ruleParserClasses.get(ruleName))) {
          map.put(ruleName, ruleMap.get(ruleName));
        }
      }
      File parserFile = new File(rootPath + File.separatorChar + className.replace('.', File.separatorChar) + ".java");
      parserFile.getParentFile().mkdirs();
      out = new PrintWriter(new FileOutputStream(parserFile));
      try {
        generateParser(className, map);
      }
      finally {
        out.close();
      }
    }
  }

  @NotNull
  private String getSuperClassName(BnfRule rule, String psiPackage, String suffix) {
    String superRuleName = getAttribute(rule, "extends", "generated.CompositeElementImpl");
    BnfRule superRule = superRuleName == null ? null : ruleMap.get(superRuleName);
    if (superRule == null) return superRuleName;
    return psiPackage + "." + getRulePsiClassName(superRule, superRule.getName(), true) + suffix;
  }

  @NotNull
  private String[] getSuperInterfaceNames(BnfRule rule, String psiPackage) {
    ArrayList<String> strings = new ArrayList<String>();
    String superRuleImplements = "";
    {
      String superRuleName = getAttribute(rule, "extends", null);
      BnfRule superRule = superRuleName == null ? null : ruleMap.get(superRuleName);
      if (superRule != null) {
        superRuleImplements = getAttribute(superRule, "implements", "generated.CompositeElement");
        strings.add(psiPackage + "." + getRulePsiClassName(superRule, superRule.getName(), true));
      }
    }
    String[] superIntfNames = getAttribute(rule, "implements", "generated.CompositeElement").split(",");
    for (String superIntfName : superIntfNames) {
      BnfRule superIntfRule = ruleMap.get(superIntfName);
      if (superIntfRule != null) {
        strings.add(psiPackage + "." + getRulePsiClassName(superIntfRule, superIntfRule.getName(), true));
      }
      else if (!superRuleImplements.contains(superIntfName)) {
        strings.add(superIntfName);
      }
    }
    return strings.toArray(new String[strings.size()]);
  }

  private static String getRulePsiClassName(BnfRule rule, String ruleName, boolean withPrefix) {
    StringBuilder sb = new StringBuilder();
    if (withPrefix) {
      sb.append(getAttribute(rule, "psiClassPrefix", ""));
    }
    for (String s : ruleName.split("_")) {
      if (s.length() == 0) continue;
      sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1).toLowerCase());
    }
    return sb.toString();
  }

  public void generateParser(String parserClass, Map<String, BnfRule> ruleMap) {
    final String elementTypeHolderClass = getRootAttribute(treeRoot, "elementTypeHolderClass", "generated.ParserTypes");
    final String stubParser = getRootAttribute(treeRoot, "stubParserClass", "generated.ParserUtil");
    final String parserImports = getRootAttribute(treeRoot, "parserImports", "");
    String rootParserClass = ruleParserClasses.get(grammarRoot);
    boolean rootParser = parserClass.equals(rootParserClass);
    generateClassHeader(parserClass,
                        "org.jetbrains.annotations.*;" +
                        "com.intellij.lang.LighterASTNode;" +
                        "com.intellij.lang.PsiBuilder;" +
                        "com.intellij.lang.PsiBuilder.Marker;" +
                        "com.intellij.openapi.diagnostic.Logger;" +
                        "static " + elementTypeHolderClass + ".*;" +
                        "static " + stubParser + ".*;" +
                        (!rootParser ? "static " + rootParserClass + ".*;" :
                         IELEMENTTYPE_CLASS + ";" +
                         "com.intellij.lang.ASTNode;" +
                         "com.intellij.psi.tree.TokenSet;" +
                         "com.intellij.lang.PsiParser;") +
                        parserImports
      , "@SuppressWarnings({\"SimplifiableIfStatement\", \"UnusedAssignment\"})",
                        false, "", rootParser ? "PsiParser" : "");

    out("public static Logger LOG_ = Logger.getInstance(\"" + parserClass + "\");");
    newLine();

    if (rootParser) {
      generateRootParserContent(ruleMap.keySet());
    }
    for (String ruleName : ruleMap.keySet()) {
      BnfRule rule = ruleMap.get(ruleName);
      if (Rule.isExternal(rule)) continue;
      out("/* ********************************************************** */");
      generateNode(rule, rule.getExpression(), Rule.isPrivate(rule), ruleName, new HashSet<BnfExpression>());
      newLine();
    }

    out("}");
  }

  private void generateRootParserContent(Set<String> ownRuleNames) {
    out("@NotNull");
    out("public ASTNode parse(final IElementType root_, final PsiBuilder builder_) {");
    out("final int level_ = 0;");
    out("boolean result_;");
    boolean first = true;
    for (String ruleName : ownRuleNames) {
      BnfRule rule = ruleMap.get(ruleName);
      if (Rule.isPrivate(rule) || Rule.isExternal(rule) || grammarRoot.equals(ruleName)) continue;
      String elementType = getElementType(rule);
      out((first ? "" : "else ") + "if (root_ == " + elementType + ") {");
      String nodeCall = generateNodeCall(rule, null, ruleName);
      out("result_ = " + nodeCall + ";");
      out("}");
      if (first) first = false;
    }
    {
      BnfRule rootRule = ruleMap.get(grammarRoot);
      String nodeCall = generateNodeCall(rootRule, null, rootRule.getName());
      if (!first) out("else {");
      out("Marker marker_ = builder_.mark();");
      out("try {");
      out("result_ = " + nodeCall + ";");
      out("while (builder_.getTokenType() != null) {");
      out("builder_.advanceLexer();");
      out("}");
      out("}");
      out("finally {");
      out("marker_.done(root_);");
      out("}");
      if (!first) out("}");
    }
    out("return builder_.getTreeBuilt();");
    out("}");
    newLine();
    if (!rulesExtendsMap.isEmpty()) {
      out("private static final TokenSet[] EXTENDS_SETS_ = new TokenSet[] {");
      for (String ruleName : rulesExtendsMap.keySet()) {
        Collection<String> strings = rulesExtendsMap.get(ruleName);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String string : strings) {
          if (i > 0 && i % 4 == 0) {
            sb.append(",\n");
          }
          else if (i > 0) sb.append(", ");
          sb.append(string);
          i++;
        }
        out("TokenSet.create(" + sb.toString() + "),");
      }
      out("};");
      out("public static boolean type_extends_(IElementType child_, IElementType parent_) {");
      out("for (TokenSet set : EXTENDS_SETS_) {");
      out("if (set.contains(child_) && set.contains(parent_)) return true;");
      out("}");
      out("return false;");
      out("}");
      newLine();
    }
  }

  private void computeInheritance() {
    final Set<String> publicRules = new THashSet<String>();
    for (String ruleName : ruleMap.keySet()) {
      BnfRule rule = ruleMap.get(ruleName);
      if (Rule.isPrivate(rule) || Rule.isExternal(rule)) continue;
      BnfRule superRule = ruleMap.get(getAttribute(rule, "extends", ""));
      String elementType = getElementType(rule);
      publicRules.add(elementType);
      if (superRule == null) continue;
      rulesExtendsMap.putValue(getElementType(superRule), elementType);

      rulesWithIheritance.add(rule);
      rulesWithIheritance.add(superRule);
    }

    for (int i = 0, len = rulesExtendsMap.size(); i < len; i++) {
      boolean changed = false;
      for (String parent : rulesExtendsMap.keySet()) {
        final Collection<String> strings = rulesExtendsMap.get(parent);
        for (String child : new ArrayList<String>(strings)) {
          changed = strings.addAll(rulesExtendsMap.get(child));
        }
      }
      if (!changed) break;
    }
    for (String ruleName : rulesExtendsMap.keySet()) {
      if (!publicRules.contains(ruleName)) continue;
      rulesExtendsMap.putValue(ruleName, ruleName); // add super to itself
    }
  }

  private void generateClassHeader(String className, String imports, String annos, boolean intf, String... supers) {
    final String classHeader = getStringOrFile(getRootAttribute(treeRoot, "classHeader", DEFAULT_FILE_HEADER, className));
    final String packageName = StringUtil.getPackageName(className);
    offset = 0;
    out(classHeader);
    out("package " + packageName + ";");
    newLine();
    for (String s : imports.split(";")) {
      if (s.startsWith(packageName + ".") && s.indexOf(".", packageName.length() + 1) == -1) {
        continue;
      }
      if (!s.contains(".")) continue;
      out("import " + s + ";");
    }
    newLine();
    StringBuilder sb = new StringBuilder();
    for (int i = 0, supersLength = supers.length; i < supersLength; i++) {
      String aSuper = supers[i];
      if (StringUtil.isEmpty(aSuper)) continue;
      if (imports.contains(aSuper + ";")) {
        aSuper = StringUtil.getShortName(aSuper);
      }
      if (i == 0) {
        sb.append(" extends ").append(aSuper);
      }
      else if (!intf && i == 1) {
        sb.append(" implements ").append(aSuper);
      }
      else {
        sb.append(", ").append(aSuper);
      }
    }
    if (StringUtil.isNotEmpty(annos)) {
      out(annos);
    }
    out("public " + (intf ? "interface " : "class ") + StringUtil.getShortName(className) + sb.toString() + " {");
    newLine();
  }

  private String getStringOrFile(String classHeader) {
    final File file = new File(VfsUtil.virtualToIoFile(treeRoot.getVirtualFile()).getParentFile(), classHeader);
    try {
      return file.exists()? FileUtil.loadFile(file) : classHeader;
    }
    catch (IOException ex) {
      LOG.error(ex);
    }
    return classHeader;
  }

  private void generateNode(BnfRule rule, BnfExpression node, boolean shouldBePrivate, String funcName, Set<BnfExpression> visited) {
    IElementType type = getEffectiveType(node);
    if (node instanceof BnfLiteralExpression ||
        node instanceof BnfReferenceOrToken && node != rule.getExpression() ||
        !visited.add(node)) {
      return;
    }
    if (node instanceof BnfExternalExpression) {
      List<BnfExpression> expressions = ((BnfExternalExpression)node).getExpressionList();
      for (int i = 1, expressionsSize = expressions.size(); i < expressionsSize; i++) {
        BnfExpression expression = expressions.get(i);
        if (expression instanceof BnfParenthesized) {
          generateNode(rule, expression, true, getNextName(funcName, i - 1), visited);
        }
      }
      return;
    }

    boolean isPrivate = shouldBePrivate || grammarRoot.equals(rule.getName());
    for (String s : StringUtil.split(node.getText(), "\n")) {
      out("// " + s);
    }
    boolean isRule = node.getParent() == rule;
    boolean firstNonTrivial = node == Rule.firstNotTrivial(rule);
    final String recoverRoot = firstNonTrivial ? Rule.attribute(rule, "recoverUntil", (String)null) : null;
    final boolean canCollapse = firstNonTrivial && rulesWithIheritance.contains(rule);

    List<BnfExpression> children = getChildExpressions(node);
    out((!isRule ? "private " : isPrivate ? "" : "public ") + "static boolean " + funcName + "(PsiBuilder builder_, final int level_"
        + collectExtraArguments(rule, true) + ") {");
    if (node instanceof BnfReferenceOrToken) {
      if (isPrivate) {
        String nodeCall = generateNodeCall(rule, node, getNextName(funcName, 0));
        out("return " + nodeCall + ";");
        out("}");
        return;
      }
      else {
        children = Collections.singletonList(node);
        type = BNF_SEQUENCE;
      }
    }
    if (children.isEmpty()) {
      out("return true;");
      out("}");
      return;
    }

    String debugFuncName = funcName; // + ":" + node.toStringTree();
    out("if (!recursion_guard_(builder_, level_, \"" + debugFuncName + "\")) return false;");

    if (isTrivialNode(node)) {
      BnfExpression child = children.get(0);
      out("return " + generateNodeCall(rule, child, getNextName(funcName, 0)) + ";");
      out("}");
      newLine();
      generateNode(rule, child, shouldBePrivate, getNextName(funcName, 0), visited);
      return;
    }
    final long funcId = StringHash.calc(funcName);
    if (generateMemoizationCode) {
      out("if (memoizedFalseBranch(builder_, " + funcId + "L) return false;");
    }

    final Object pinValue = type == BNF_SEQUENCE ? getAttribute(rule, "pin", null, firstNonTrivial? rule.getName() : funcName) : null;
    final int pinIndex = pinValue instanceof Integer ? (Integer)pinValue : -1;
    final Pattern pinPattern = pinValue instanceof String ? Pattern.compile(StringUtil.unescapeStringCharacters((String)pinValue)) : null;
    boolean pinApplied = false;

    out("boolean result_ = " + (type == BNF_OP_ZEROMORE || type == BNF_OP_OPT) + ";");
    boolean pinned = pinIndex > -1 || pinPattern != null;
    if (pinned) {
      out("boolean pinned_ = false;");
    }
    if (!isPrivate && canCollapse) {
      out("final int start_ = builder_.getCurrentOffset();");
    }
    out("final Marker marker_ = builder_.mark();");
    out("try {");

    String sectionType =
      recoverRoot != null ? "_SECTION_RECOVER_" : type == BNF_OP_AND ? "_SECTION_AND_" : type == BNF_OP_NOT ? "_SECTION_NOT_" :
                                                                                         pinned ? "_SECTION_GENERAL_" : null;
    if (sectionType != null) {
      out("enterErrorRecordingSection(builder_, level_, " + sectionType + ");");
    }

    for (int i = 0, childrenSize = children.size(); i < childrenSize; i++) {
      BnfExpression child = children.get(i);

      String nodeCall = generateNodeCall(rule, child, getNextName(funcName, i));
      if (type == BNF_CHOICE) {
        out((i > 0 ? "if (!result_) " : "") + "result_ = " + nodeCall + ";");
      }
      else if (type == BNF_SEQUENCE) {
        if (i > 0) {
          out("result_ = result_ && " + nodeCall + ";");
        }
        else {
          out("result_ = " + nodeCall + ";");
        }
        if (!pinApplied && (i == pinIndex - 1 || pinPattern != null && pinPattern.matcher(child.getText()).matches())) {
          pinApplied = true;
          out("pinned_ = result_; // pin = " + pinValue);
        }
      }
      else if (type == BNF_OP_OPT) {
        out(nodeCall + ";");
      }
      else if (type == BNF_OP_ONEMORE || type == BNF_OP_ZEROMORE) {
        if (type == BNF_OP_ONEMORE) {
          out("result_ = " + nodeCall + ";");
          nodeCall = generateNodeCall(rule, child, getNextName(funcName, i));
        }
        out("int offset_ = builder_.getCurrentOffset();");
        out("while (result_ && !builder_.eof()) {");
        out("if (!" + nodeCall + ") break;");
        out("if (offset_ == builder_.getCurrentOffset()) {");
        out("builder_.error(\"Empty element parsed in " + debugFuncName + "\");");
        out("break;");
        out("}");
        out("offset_ = builder_.getCurrentOffset();");
        out("}");
      }
      else if (type == BNF_OP_AND) {
        out("result_ = " + nodeCall + ";");
      }
      else if (type == BNF_OP_NOT) {
        out("result_ = !" + nodeCall + ";");
      }
      else {
        throw new AssertionError("unexpected: " + type);
      }
    }
    out("}");
    out("finally {");

    if (type == BNF_OP_AND || type == BNF_OP_NOT) {
      out("marker_.rollbackTo();");
    }
    else if (!isPrivate) {
      String elementType = getElementType(rule);
      if (canCollapse) {
        out("LighterASTNode last_ = result_? builder_.getLatestDoneMarker() : null;");
        out("if (last_ != null && last_.getStartOffset() == start_ && type_extends_(last_.getTokenType(), " + elementType + ")) {");
        out("marker_.drop();");
        out("}");
        out("else if (result_" + (pinned ? " || pinned_" : "") + ") {");
      }
      else {
        out("if (result_" + (pinned ? " || pinned_" : "") + ") {");
      }
      out("marker_.done(" + elementType + ");");
      out("}");
      out("else {");
      out("marker_.rollbackTo();");
      out("}");
    }
    else {
      if (type == BNF_OP_OPT || type == BNF_OP_ZEROMORE) {
        out("marker_.drop();");
      }
      else {
        out("if (!result_" + (pinned ? " && !pinned_" : "") + ") {");
        out("marker_.rollbackTo();");
        out("}");
        out("else {");
        out("marker_.drop();");
        out("}");
      }
    }
    if (sectionType != null) {
      final String untilCall;
      if (recoverRoot != null) {
        BnfRule untilRule = ruleMap.get(recoverRoot);
        untilCall = untilRule == null ? null : generateWrappedNodeCall(rule, null, untilRule.getName());
      }
      else {
        untilCall = null;
      }
      if (untilCall != null) {
        out("result_ = exitErrorRecordingSection(builder_, result_, level_, " +
            (pinned ? "pinned_" : "false") +
            ", " +
            sectionType +
            ", " +
            untilCall +
            ");");
      }
      else {
        out("result_ = exitErrorRecordingSection(builder_, result_, level_, " +
            (pinned ? "pinned_" : "false") +
            ", " +
            sectionType +
            ", null);");
      }
    }

    out("}");

    if (generateMemoizationCode) {
      out("if (!result_" + (pinned ? " && !pinned_" : "") + ") memoizeFalseBranch(builder_, " + funcId + ")");
    }
    out("return result_" + (pinned ? " || pinned_" : "") + ";");
    out("}");
    newLine();
    for (int i = 0, len = children.size(); i < len; i++) {
      generateNode(rule, children.get(i), true, getNextName(funcName, i), visited);
    }
  }

  private String collectExtraArguments(BnfRule rule, final boolean declaration) {
    if (!Rule.isMeta(rule)) return "";
    final StringBuilder sb = new StringBuilder();
    final Set<String> visited = new THashSet<String>();
    rule.getExpression().acceptChildren(new PsiRecursiveElementWalkingVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        if (element instanceof BnfExternalExpression) {
          List<BnfExpression> list = ((BnfExternalExpression)element).getExpressionList();
          if (list.size() == 1) {
            String text = list.get(0).getText();
            if (visited.add(text)) {
              sb.append(", "+ (declaration? "Parser " : "") + text);
            }
          }
        }
        super.visitElement(element);
      }
    });
    return sb.toString();
  }

  private String generateNodeCall(BnfRule rule, @Nullable BnfExpression node, String nextName) {
    IElementType type = node == null ? BNF_REFERENCE_OR_TOKEN : getEffectiveType(node);
    String text = node == null ? nextName : node.getText();
    if (type == BNF_STRING) {
      String value = StringUtil.stripQuotesAroundValue(text);
      String attributeName = getAttributeName(rule, value);
      if (attributeName != null) {
        return generateConsumeToken(attributeName);
      }
      return generateConsumeTextToken(value);
    }
    else if (type == BNF_NUMBER) {
      return generateConsumeTextToken(text);
    }
    else if (type == BNF_REFERENCE_OR_TOKEN) {
      BnfRule subRule = ruleMap.get(text);
      if (subRule != null) {
        String method;
        if (Rule.isExternal(subRule)) {
          StringBuilder clause = new StringBuilder();
          BnfExpression expression = subRule.getExpression();
          List<BnfExpression> expressions =
            expression instanceof BnfSequence ? ((BnfSequence)expression).getExpressionList() : Collections.singletonList(expression);
          method = generateExternalCall(rule, clause, expressions, nextName);
          return method + "(builder_, level_ + 1" + clause.toString() + ")";
        }
        else {
          method = subRule.getName();
          String parserClass = ruleParserClasses.get(method);
          if (!parserClass.equals(ruleParserClasses.get(rule.getName()))) {
            method = StringUtil.getShortName(parserClass) + "." + method;
          }
          return method + "(builder_, level_ + 1"+collectExtraArguments(rule, false)+")";
        }
      }
      return generateConsumeToken(text);
    }
    else if (type == BNF_EXTERNAL_EXPRESSION) {
      List<BnfExpression> expressions = ((BnfExternalExpression)node).getExpressionList();
      if (expressions.size() == 1 && Rule.isMeta(rule)) {
        return expressions.get(0).getText() + ".parse(builder_)";
      }
      else {
        StringBuilder clause = new StringBuilder();
        String method = generateExternalCall(rule, clause, expressions, nextName);
        return method + "(builder_, level_ + 1" + clause.toString() + ")";
      }
    }
    else {
      return nextName + "(builder_, level_ + 1"+ collectExtraArguments(rule, false)+")";
    }
  }

  private String generateExternalCall(BnfRule rule, StringBuilder clause, List<BnfExpression> expressions, String nextName) {
    String method;
    method = expressions.size() > 0 ? expressions.get(0).getText() : null;
    if (expressions.size() > 1) {
      for (int i = 1, len = expressions.size(); i < len; i++) {
        clause.append(", ");
        BnfExpression nested = expressions.get(i);
        String argument = nested.getText();
        if (nested instanceof BnfReferenceOrToken) {
          if (ruleMap.containsKey(argument)) {
            clause.append(generateWrappedNodeCall(rule, nested, argument));
          }
          else {
            //clause.append(getElementType(argument));
            clause.append(argument);
          }
        }
        else if (nested instanceof BnfParenthesized) {
          clause.append(generateWrappedNodeCall(rule, nested, getNextName(nextName, i - 1)));
        }
        else {
          clause.append(argument);
        }
      }
    }
    return method;
  }

  private String generateWrappedNodeCall(BnfRule rule, @Nullable BnfExpression nested, final String text) {
    return "\nnew Parser() { public boolean parse(PsiBuilder builder_) { return " + generateNodeCall(rule, nested, text) + "; }}";
  }

  private static String generateConsumeTextToken(String tokenText) {
    return "consumeToken(builder_, \"" + tokenText + "\")";
  }

  private String generateConsumeToken(String tokenName) {
    simpleTokens.add(tokenName);
    return "consumeToken(builder_, " + getElementType(tokenName) + ")";
  }

  private static String getElementType(BnfRule rule) {
    String elementType = Rule.attribute(rule, "elementType", rule.getName());
    return getAttribute(rule, "elementTypePrefix", "") + elementType.toUpperCase();
  }

  private String getElementType(String token) {
    return getRootAttribute(treeRoot, "elementTypePrefix", "") + token.toUpperCase();
  }

  /*ElementTypes******************************************************************/

  private void generateElementTypesHolder(String className, boolean generatePsi) {
    String implPackage = getRootAttribute(treeRoot, "psiImplPackage", "generated.psi.impl");
    final String elementTypeClass = getRootAttribute(treeRoot, "elementTypeClass", IELEMENTTYPE_CLASS);
    final String elementTypeFactory = getRootAttribute(treeRoot, "elementTypeFactory", null);
    final String tokenTypeClass = getRootAttribute(treeRoot, "tokenTypeClass", IELEMENTTYPE_CLASS);
    final String tokenTypeFactory = getRootAttribute(treeRoot, "tokenTypeFactory", null);
    generateClassHeader(className,
                        IELEMENTTYPE_CLASS + ";" +
                        "com.intellij.psi.PsiElement;" +
                        "com.intellij.lang.ASTNode;" +
                        elementTypeClass + ";" +
                        (elementTypeFactory == null ? "" : "static " + elementTypeFactory + ";") +
                        tokenTypeClass + ";" +
                        (tokenTypeFactory == null ? "" : "static " + tokenTypeFactory + ";") +
                        (generatePsi ? implPackage + ".*;" : ""), "", true);
    final Set<String> visited = new THashSet<String>();
    String elementCreateCall =
      elementTypeFactory == null ? "new " + StringUtil.getShortName(elementTypeClass) : StringUtil.getShortName(elementTypeFactory);
    for (String ruleName : ruleMap.keySet()) {
      final BnfRule rule = ruleMap.get(ruleName);
      if (Rule.isPrivate(rule) || Rule.isExternal(rule) || grammarRoot.equals(ruleName)) continue;
      final String elementType = getElementType(rule);
      if (!visited.add(elementType)) continue;
      out("IElementType " + elementType + " = "
          + elementCreateCall + "(\"" + elementType + "\");");
    }
    newLine();
    String tokenCreateCall =
      tokenTypeFactory == null ? "new " + StringUtil.getShortName(tokenTypeClass) : StringUtil.getShortName(tokenTypeFactory);
    for (String token : simpleTokens) {
      String name = getRootAttribute(treeRoot, token, token);
      out("IElementType " + getElementType(token) + " = "
          + tokenCreateCall + "(\"" + name + "\");");
    }
    newLine();
    if (generatePsi) {
      out("class Factory {");
      out("public static PsiElement createElement(ASTNode node) {");
      out("IElementType type = node.getElementType();");
      String suffix = getRootAttribute(treeRoot, "psiImplClassSuffix", "Impl");
      visited.clear();
      boolean first = true;
      for (String ruleName : ruleMap.keySet()) {
        final BnfRule rule = ruleMap.get(ruleName);
        if (Rule.isPrivate(rule) || Rule.isExternal(rule) || grammarRoot.equals(ruleName)) continue;
        String psiClass = getRulePsiClassName(rule, ruleName, true) + suffix;
        String elementType = getElementType(rule);
        if (!visited.add(elementType)) continue;
        out((!first ? "else " : "") + " if (type == " + elementType + ") {");
        out("return new " + psiClass + "(node);");
        first = false;
        out("}");
      }
      out("throw new AssertionError(\"Unknown element type: \" + type);");

      out("}");
      out("}");
    }
    out("}");
  }


  /*PSI******************************************************************/
  private void generatePsiIntf(RuleGraphHelper helper, BnfRule rule, String psiClass, String... psiSupers) {
    Map<PsiElement, RuleGraphHelper.Cardinality> accessors = helper.getFor(rule);
    final Collection<BnfRule> sortedPublicRules = getSortedPublicRules(accessors.keySet());

    generateClassHeader(psiClass, "java.util.List;" +
                                  "org.jetbrains.annotations.*;" +
                                  "com.intellij.psi.PsiElement;" +
                                  StringUtil.join(psiSupers, ";") + ";" +
                                  StringUtil.join(getRuleAccessorClasses(rule, sortedPublicRules), ";"),
                        "", true, psiSupers);
    for (PsiElement tree : sortedPublicRules) {
      generatePsiAccessor(rule, tree, accessors.get(tree), true);
    }
    for (BnfReferenceOrToken tree : getSortedSimpleTokens(accessors.keySet())) {
      generatePsiAccessor(rule, tree, accessors.get(tree), true);
    }
    out("}");
  }

  private void generatePsiImpl(RuleGraphHelper helper,
                               BnfRule rule,
                               String psiClass,
                               String superInterface,
                               String superRuleClass) {
    String typeHolderClass = getRootAttribute(treeRoot, "elementTypeHolderClass", "generated.ParserTypes");
    // mixin attribute overrides "extends":
    String implSuper = getAttribute(rule, "mixin", superRuleClass);
    Map<PsiElement, RuleGraphHelper.Cardinality> accessors = helper.getFor(rule);
    final Collection<BnfRule> sortedPublicRules = getSortedPublicRules(accessors.keySet());
    generateClassHeader(psiClass, "java.util.List;" +
                                  "org.jetbrains.annotations.*;" +
                                  "com.intellij.lang.ASTNode;" +
                                  "com.intellij.psi.PsiElement;" +
                                  "com.intellij.psi.util.PsiTreeUtil;" +
                                  "static " + typeHolderClass + ".*;" +
                                  (StringUtil.isNotEmpty(implSuper) ? implSuper + ";" : "") +
                                  StringUtil.getPackageName(superInterface) + ".*;" +
                                  StringUtil.join(getRuleAccessorClasses(rule, sortedPublicRules), ";"),
                        "", false, StringUtil.getShortName(implSuper),
                        StringUtil.getShortName(superInterface));
    out("public " + StringUtil.getShortName(psiClass) + "(ASTNode node) {");
    out("super(node);");
    out("}");
    newLine();
    for (BnfRule tree : sortedPublicRules) {
      generatePsiAccessor(rule, tree, accessors.get(tree), false);
    }
    for (BnfReferenceOrToken tree : getSortedSimpleTokens(accessors.keySet())) {
      generatePsiAccessor(rule, tree, accessors.get(tree), false);
    }
    out("}");
  }

  private void generatePsiAccessor(BnfRule rule, PsiElement tree, RuleGraphHelper.Cardinality type, boolean intf) {
    BnfRule treeRule = tree instanceof BnfRule ? (BnfRule)tree : null;

    boolean many = type == AT_LEAST_ONE || type == ANY_NUMBER;

    String ruleName;
    if (treeRule == null) {
      IElementType effectiveType = getEffectiveType(tree);
      if (effectiveType == BNF_STRING) {
        //String value = StringUtil.stripQuotesAroundValue(tree.getText());
        //ruleName = getAttributeName(rule, value);
        return; // do not generate
      }
      else if (effectiveType == BNF_REFERENCE_OR_TOKEN) {
        ruleName = tree.getText();
        // generate lowercase ident, id, string, etc
        if (!ruleName.toLowerCase().equals(ruleName)) return;
        if (many) return;
      }
      else {
        ruleName = null; // do not bother generate numbers & simple literals
      }
    }
    else {
      ruleName = treeRule.getName();
    }
    if (ruleName == null) return;
    String getterNameBody = getAttribute(rule, "methodRenames", "get" + getRulePsiClassName(rule, ruleName, false), ruleName);
    String getterName = getterNameBody + (many ? "List" : "");
    if (!intf) out("@Override");
    if (type == REQUIRED) {
      out("@NotNull");
    }
    else if (type == OPTIONAL) {
      out("@Nullable");
    }
    else {
      out("@NotNull");
    }
    String className = StringUtil.getShortName(getAccessorType(rule, tree));
    String tail = intf ? "();" : "() {";
    out("public " + (many ? "List<" : "") + className + (many ? "> " : " ") + getterName + tail);
    if (!intf) {
      if (treeRule == null) {
        out("ASTNode child = getNode().findChildByType(" + getElementType(ruleName) + ");");
        out("return child == null? null : child.getPsi();");
      }
      else {
        if (many) {
          out("return PsiTreeUtil.getChildrenOfTypeAsList(this, " + className + ".class);");
        }
        else {
          out("return PsiTreeUtil.getChildOfType(this, " + className + ".class);");
        }
      }
      out("}");
    }
    newLine();
  }

  private static String getAccessorType(BnfRule rule, PsiElement tree) {
    BnfRule treeRule = tree instanceof BnfRule ? (BnfRule)tree : null;
    return treeRule == null ? PSI_ELEMENT_CLASS :
           Rule.isExternal(treeRule) ? getAttribute(treeRule, "implements", PSI_ELEMENT_CLASS) :
           getRulePsiClassName(rule, treeRule.getName(), true);
  }

  private static Collection<BnfRule> getSortedPublicRules(Set<PsiElement> accessors) {
    Map<String, BnfRule> result = new TreeMap<String, BnfRule>();
    for (PsiElement tree : accessors) {
      if (tree instanceof BnfRule) {
        BnfRule rule = (BnfRule)tree;
        if (!Rule.isPrivate(rule)) result.put(rule.getName(), rule);
      }
    }
    return result.values();
  }

  private Collection<BnfReferenceOrToken> getSortedSimpleTokens(Set<PsiElement> accessors) {
    TreeMap<String, BnfReferenceOrToken> result = new TreeMap<String, BnfReferenceOrToken>();
    for (PsiElement tree : accessors) {
      if (!(tree instanceof BnfReferenceOrToken)) continue;
      if (simpleTokens.contains(tree.getText()) /*|| type == STRING || type == NUMBER*/) {
        result.put(tree.getText(), (BnfReferenceOrToken)tree);
      }
    }
    return result.values();
  }

  private static Collection<String> getRuleAccessorClasses(BnfRule rule, Collection<BnfRule> bnfRules) {
    final TreeSet<String> result = new TreeSet<String>();
    for (BnfRule r : bnfRules) {
      result.add(getAccessorType(rule, r));
    }
    return result;
  }
}