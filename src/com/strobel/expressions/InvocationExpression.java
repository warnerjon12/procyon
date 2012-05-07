package com.strobel.expressions;

import com.strobel.reflection.Type;

/**
 * @author Mike Strobel
 */
public final class InvocationExpression extends Expression implements IArgumentProvider {
    private final ExpressionList<? extends Expression> _arguments;
    private final LambdaExpression _lambda;
    private final Type _returnType;

    InvocationExpression(final LambdaExpression lambda, final ExpressionList<? extends Expression> arguments, final Type returnType) {
        _lambda = lambda;
        _arguments = arguments;
        _returnType = returnType;
    }

    @Override
    public final Type getType() {
        return _returnType;
    }

    @Override
    public final ExpressionType getNodeType() {
        return ExpressionType.Invoke;
    }

    public LambdaExpression getExpression() {
        return _lambda;
    }

    public ExpressionList getArguments() {
        return _arguments;
    }

    @Override
    public int getArgumentCount() {
        return _arguments.size();
    }

    @Override
    public Expression getArgument(final int index) {
        return _arguments.get(index);
    }

    public InvocationExpression update(final LambdaExpression lambda, final ExpressionList<? extends Expression> arguments) {
        if (lambda == _lambda && arguments == _arguments) {
            return this;
        }
        return invoke(lambda, arguments);
    }

    @Override
    protected Expression accept(final ExpressionVisitor visitor) {
        return visitor.visitInvocation(this);
    }

    InvocationExpression rewrite(final LambdaExpression lambda, final ExpressionList<? extends Expression> arguments) {
        assert lambda != null;
        assert arguments == null || arguments.size() == _arguments.size();

        return Expression.invoke(lambda, arguments != null ? arguments : _arguments);
    }
}
