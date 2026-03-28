package io.nozdormu.spi.error;

public class InjectionProcessException extends RuntimeException {

  public InjectionProcessException(InjectionProcessErrorType injectionProcessErrorType) {
    super(injectionProcessErrorType.format());
  }

  public InjectionProcessException(
      InjectionProcessErrorType injectionProcessErrorType, Object... variables) {
    super(injectionProcessErrorType.format(variables));
  }
}
