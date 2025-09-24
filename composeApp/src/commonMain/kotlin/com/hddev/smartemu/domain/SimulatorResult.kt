package com.hddev.smartemu.domain

/**
 * Type alias for Result with SimulatorError as the failure type.
 * Provides a consistent way to handle errors throughout the application.
 */
typealias SimulatorResult<T> = Result<T>

/**
 * Extension functions for working with SimulatorResult.
 */
object SimulatorResultExtensions {
    
    /**
     * Creates a successful SimulatorResult.
     */
    fun <T> success(value: T): SimulatorResult<T> = Result.success(value)
    
    /**
     * Creates a failed SimulatorResult with a SimulatorError.
     */
    fun <T> failure(error: SimulatorError): SimulatorResult<T> = Result.failure(error)
    
    /**
     * Creates a failed SimulatorResult from a Throwable.
     */
    fun <T> failure(throwable: Throwable): SimulatorResult<T> = 
        Result.failure(SimulatorError.SystemError.UnexpectedError(throwable))
}

/**
 * Extension function to get SimulatorError from a failed Result.
 */
fun <T> Result<T>.getSimulatorError(): SimulatorError? {
    return exceptionOrNull() as? SimulatorError
}

/**
 * Extension function to get user-friendly error message from a failed Result.
 */
fun <T> Result<T>.getUserMessage(): String? {
    return getSimulatorError()?.toUserMessage()
}

/**
 * Extension function to check if the error is recoverable.
 */
fun <T> Result<T>.isRecoverable(): Boolean {
    return getSimulatorError()?.isRecoverable() ?: false
}

/**
 * Extension function to map a Result to SimulatorResult, converting exceptions to SimulatorError.
 */
inline fun <T> Result<T>.toSimulatorResult(): SimulatorResult<T> {
    return fold(
        onSuccess = { SimulatorResultExtensions.success(it) },
        onFailure = { throwable ->
            when (throwable) {
                is SimulatorError -> Result.failure(throwable)
                else -> SimulatorResultExtensions.failure(throwable)
            }
        }
    )
}

/**
 * Extension function to safely execute a block and return a SimulatorResult.
 */
inline fun <T> safeCall(block: () -> T): SimulatorResult<T> {
    return try {
        SimulatorResultExtensions.success(block())
    } catch (e: SimulatorError) {
        Result.failure(e)
    } catch (e: Exception) {
        SimulatorResultExtensions.failure(e)
    }
}

/**
 * Extension function to safely execute a suspend block and return a SimulatorResult.
 */
suspend inline fun <T> safeSuspendCall(crossinline block: suspend () -> T): SimulatorResult<T> {
    return try {
        SimulatorResultExtensions.success(block())
    } catch (e: SimulatorError) {
        Result.failure(e)
    } catch (e: Exception) {
        SimulatorResultExtensions.failure(e)
    }
}