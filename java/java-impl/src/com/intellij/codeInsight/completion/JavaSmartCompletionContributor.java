// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.*;
import com.intellij.codeInsight.completion.scope.JavaCompletionProcessor;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.patterns.ElementPattern;
import com.intellij.psi.*;
import com.intellij.psi.filters.ElementExtractorFilter;
import com.intellij.psi.filters.ElementFilter;
import com.intellij.psi.filters.OrFilter;
import com.intellij.psi.filters.getters.ExpectedTypesGetter;
import com.intellij.psi.filters.types.AssignableFromFilter;
import com.intellij.psi.filters.types.AssignableToFilter;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.proximity.ReferenceListWeigher;
import com.intellij.util.Consumer;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.patterns.PlatformPatterns.psiElement;
import static com.intellij.patterns.StandardPatterns.or;

/**
 * @author peter
 */
public class JavaSmartCompletionContributor {
  private static final TObjectHashingStrategy<ExpectedTypeInfo> EXPECTED_TYPE_INFO_STRATEGY = new TObjectHashingStrategy<ExpectedTypeInfo>() {
    @Override
    public int computeHashCode(ExpectedTypeInfo object) {
      return object.getType().hashCode();
    }

    @Override
    public boolean equals(ExpectedTypeInfo o1, ExpectedTypeInfo o2) {
      return o1.getType().equals(o2.getType());
    }
  };

  private static final ElementExtractorFilter THROWABLES_FILTER = new ElementExtractorFilter(new AssignableFromFilter(CommonClassNames.JAVA_LANG_THROWABLE));
  public static final ElementPattern<PsiElement> AFTER_NEW =
      psiElement().afterLeaf(
          psiElement().withText(PsiKeyword.NEW).andNot(
              psiElement().afterLeaf(
                  psiElement().withText(PsiKeyword.THROW))));
  static final ElementPattern<PsiElement> AFTER_THROW_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW).afterLeaf(PsiKeyword.THROW));
  public static final ElementPattern<PsiElement> INSIDE_EXPRESSION = or(
        psiElement().withParent(PsiExpression.class)
          .andNot(psiElement().withParent(PsiLiteralExpression.class))
          .andNot(psiElement().withParent(PsiMethodReferenceExpression.class)),
        psiElement().inside(PsiClassObjectAccessExpression.class),
        psiElement().inside(PsiThisExpression.class),
        psiElement().inside(PsiSuperExpression.class));
  static final ElementPattern<PsiElement> INSIDE_TYPECAST_EXPRESSION = psiElement().withParent(
    psiElement(PsiReferenceExpression.class).afterLeaf(psiElement().withText(")").withParent(PsiTypeCastExpression.class)));

  @Nullable
  private static ElementFilter getClassReferenceFilter(final PsiElement element, final boolean inRefList) {
    //throw new foo
    if (AFTER_THROW_NEW.accepts(element)) {
      return THROWABLES_FILTER;
    }

    //new xxx.yyy
    if (psiElement().afterLeaf(psiElement().withText(".")).withSuperParent(2, psiElement(PsiNewExpression.class)).accepts(element)) {
      if (((PsiNewExpression)element.getParent().getParent()).getClassReference() == element.getParent()) {
        PsiType[] types = ExpectedTypesGetter.getExpectedTypes(element, false);
        return new OrFilter(ContainerUtil.map2Array(types, ElementFilter.class, type -> new AssignableFromFilter(type)));
      }
    }

    // extends/implements/throws
    if (inRefList) {
      return new ElementExtractorFilter(new ElementFilter() {
        @Override
        public boolean isAcceptable(Object aClass, @Nullable PsiElement context) {
          return aClass instanceof PsiClass && ReferenceListWeigher.INSTANCE.getApplicability((PsiClass)aClass, element) !=
                                               ReferenceListWeigher.ReferenceListApplicability.inapplicable;
        }

        @Override
        public boolean isClassAcceptable(Class hintClass) {
          return true;
        }
      });
    }

    return null;
  }

  private static void addClassReferenceSuggestions(@NotNull CompletionParameters parameters,
                                                   @NotNull CompletionResultSet result,
                                                   PsiElement element,
                                                   PsiJavaCodeReferenceElement reference) {
    boolean inRefList = ReferenceListWeigher.INSIDE_REFERENCE_LIST.accepts(element);
    ElementFilter filter = getClassReferenceFilter(element, inRefList);
    if (filter != null) {
      final List<ExpectedTypeInfo> infos = Arrays.asList(getExpectedTypes(parameters));
      for (LookupElement item : completeReference(element, reference, filter, true, false, parameters, result.getPrefixMatcher())) {
        Object o = item.getObject();
        if (o instanceof PsiClass ||
            CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION &&
            JavaConstructorCallElement.isConstructorCallPlace(element) && o instanceof PsiMethod && ((PsiMethod)o).isConstructor()) {
          if (!inRefList && o instanceof PsiClass) {
            item = LookupElementDecorator.withInsertHandler(item, ConstructorInsertHandler.SMART_INSTANCE);
          }
          result.addElement(decorate(item, infos));
        }
      }
    }
    else if (INSIDE_TYPECAST_EXPRESSION.accepts(element)) {
      final PsiTypeCastExpression cast = PsiTreeUtil.getContextOfType(element, PsiTypeCastExpression.class, true);
      if (cast != null && cast.getCastType() != null) {
        filter = new AssignableToFilter(cast.getCastType().getType());
        for (final LookupElement item : completeReference(element, reference, filter, false, true, parameters, result.getPrefixMatcher())) {
          result.addElement(item);
        }
      }
    }
  }

  private static void addExpressionSuggestions(CompletionParameters params,
                                               CompletionResultSet result,
                                               Collection<? extends ExpectedTypeInfo> _infos) {
    Consumer<LookupElement> noTypeCheck = decorateWithoutTypeCheck(result, _infos);

    THashSet<ExpectedTypeInfo> mergedInfos = new THashSet<>(_infos, EXPECTED_TYPE_INFO_STRATEGY);
    List<Runnable> chainedEtc = new ArrayList<>();
    for (final ExpectedTypeInfo info : mergedInfos) {
      Runnable slowContinuation =
        ReferenceExpressionCompletionContributor.fillCompletionVariants(new JavaSmartCompletionParameters(params, info), noTypeCheck);
      ContainerUtil.addIfNotNull(chainedEtc, slowContinuation);
    }

    for (final ExpectedTypeInfo info : mergedInfos) {
      BasicExpressionCompletionContributor.fillCompletionVariants(new JavaSmartCompletionParameters(params, info), lookupElement -> {
        final PsiType psiType = JavaCompletionUtil.getLookupElementType(lookupElement);
        if (psiType != null && info.getType().isAssignableFrom(psiType)) {
          result.addElement(decorate(lookupElement, _infos));
        }
      }, result.getPrefixMatcher());

    }

    for (Runnable runnable : chainedEtc) {
      runnable.run();
    }
  }

  @NotNull
  private static Consumer<LookupElement> decorateWithoutTypeCheck(final CompletionResultSet result, final Collection<? extends ExpectedTypeInfo> infos) {
    return lookupElement -> result.addElement(decorate(lookupElement, infos));
  }

  static void provideSmartCompletionSuggestions(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    PsiElement position = parameters.getPosition();
    if (position instanceof PsiComment || position.getParent() instanceof PsiLiteralExpression) {
      return;
    }

    if (!SmartCastProvider.shouldSuggestCast(parameters)) {
      PsiJavaCodeReferenceElement reference =
        PsiTreeUtil.findElementOfClassAtOffset(position.getContainingFile(), parameters.getOffset(), PsiJavaCodeReferenceElement.class, false);
      if (reference != null) {
        addClassReferenceSuggestions(parameters, result, position, reference);
      }

      if (INSIDE_EXPRESSION.accepts(position)) {
        addExpressionSuggestions(parameters, result, ContainerUtil.newHashSet(getExpectedTypes(parameters)));
      }
    }
    if (InstanceofTypeProvider.AFTER_INSTANCEOF.accepts(position)) {
      InstanceofTypeProvider.addCompletions(parameters, result);
    }
    if (ExpectedAnnotationsProvider.ANNOTATION_ATTRIBUTE_VALUE.accepts(position)) {
      ExpectedAnnotationsProvider.addCompletions(position, result);
    }
    if (CatchTypeProvider.CATCH_CLAUSE_TYPE.accepts(position)) {
      CatchTypeProvider.addCompletions(parameters, result);
    }
    if (psiElement().afterLeaf("::").accepts(position)) {
      MethodReferenceCompletionProvider.addCompletions(parameters, result);
    }
  }

  public static SmartCompletionDecorator decorate(LookupElement lookupElement, Collection<? extends ExpectedTypeInfo> infos) {
    return new SmartCompletionDecorator(lookupElement, infos);
  }

  public static ExpectedTypeInfo @NotNull [] getExpectedTypes(final CompletionParameters parameters) {
    return getExpectedTypes(parameters.getPosition(), parameters.getCompletionType() == CompletionType.SMART);
  }

  public static ExpectedTypeInfo @NotNull [] getExpectedTypes(PsiElement position, boolean voidable) {
    if (psiElement().withParent(psiElement(PsiReferenceExpression.class).withParent(PsiThrowStatement.class)).accepts(position)) {
      final PsiElementFactory factory = JavaPsiFacade.getElementFactory(position.getProject());
      final PsiClassType classType = factory
          .createTypeByFQClassName(CommonClassNames.JAVA_LANG_RUNTIME_EXCEPTION, position.getResolveScope());
      final List<ExpectedTypeInfo> result = new SmartList<>();
      result.add(new ExpectedTypeInfoImpl(classType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, classType, TailType.SEMICOLON, null, ExpectedTypeInfoImpl.NULL));
      final PsiMethod method = PsiTreeUtil.getContextOfType(position, PsiMethod.class, true);
      if (method != null) {
        for (final PsiClassType type : method.getThrowsList().getReferencedTypes()) {
          result.add(new ExpectedTypeInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, TailType.SEMICOLON, null, ExpectedTypeInfoImpl.NULL));
        }
      }
      return result.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
    }

    PsiExpression expression = PsiTreeUtil.getContextOfType(position, PsiExpression.class, true);
    if (expression == null) return ExpectedTypeInfo.EMPTY_ARRAY;

    return ExpectedTypesProvider.getExpectedTypes(expression, true, voidable, false);
  }

  static Set<LookupElement> completeReference(final PsiElement element,
                                              PsiJavaCodeReferenceElement reference,
                                              final ElementFilter filter,
                                              final boolean acceptClasses,
                                              final boolean acceptMembers,
                                              CompletionParameters parameters, final PrefixMatcher matcher) {
    ElementFilter checkClass = new ElementFilter() {
      @Override
      public boolean isAcceptable(Object element, PsiElement context) {
        return filter.isAcceptable(element, context);
      }

      @Override
      public boolean isClassAcceptable(Class hintClass) {
        if (ReflectionUtil.isAssignable(PsiClass.class, hintClass)) {
          return acceptClasses;
        }

        if (ReflectionUtil.isAssignable(PsiVariable.class, hintClass) ||
            ReflectionUtil.isAssignable(PsiMethod.class, hintClass) ||
            ReflectionUtil.isAssignable(CandidateInfo.class, hintClass)) {
          return acceptMembers;
        }
        return false;
      }
    };
    JavaCompletionProcessor.Options options =
      JavaCompletionProcessor.Options.DEFAULT_OPTIONS.withFilterStaticAfterInstance(parameters.getInvocationCount() <= 1);
    return JavaCompletionUtil.processJavaReference(element, reference, checkClass, options, matcher, parameters);
  }

  static void beforeSmartCompletion(@NotNull CompletionInitializationContext context) {
    if (!context.getEditor().getSelectionModel().hasSelection()) {
      final PsiFile file = context.getFile();
      PsiElement element = file.findElementAt(context.getStartOffset());
      if (element instanceof PsiIdentifier) {
        element = element.getParent();
        while (element instanceof PsiJavaCodeReferenceElement || element instanceof PsiCall ||
               element instanceof PsiThisExpression || element instanceof PsiSuperExpression ||
               element instanceof PsiTypeElement ||
               element instanceof PsiClassObjectAccessExpression) {
          int newEnd = element.getTextRange().getEndOffset();
          if (element instanceof PsiMethodCallExpression) {
            newEnd = ((PsiMethodCallExpression)element).getMethodExpression().getTextRange().getEndOffset();
          }
          else if (element instanceof PsiNewExpression) {
            final PsiJavaCodeReferenceElement classReference = ((PsiNewExpression)element).getClassReference();
            if (classReference != null) {
              newEnd = classReference.getTextRange().getEndOffset();
            }
          }
          context.setReplacementOffset(newEnd);
          element = element.getParent();
        }
      }
    }

    PsiElement lastElement = context.getFile().findElementAt(context.getStartOffset() - 1);
    if (lastElement != null && lastElement.getText().equals("(") && lastElement.getParent() instanceof PsiParenthesizedExpression) {
      // don't trim dummy identifier or we won't be able to determine the type of the expression after '('
      // which is needed to insert correct cast
      return;
    }
    context.setDummyIdentifier(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED);
  }
}