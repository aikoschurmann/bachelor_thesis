# Methodology: Statistical Framework for Robust Sampling

To ensure the generation of high-quality training data for the Learning to Rank (LTR) model, we established a statistical framework to determine the minimum sampling budget ($N$) required to capture rare, optimized "Oracle" trajectories. This framework is designed to be robust against the heavy-tailed, asymmetric nature of program analysis costs.

## A. Distributional Validation and Log-Normality

We analysed that the computational cost of worklist algorithms, denoted by the random variable $\mathcal{X}$ (iterations), follows a Normal distribution due to the multiplicative branching factors inherent in state-space exploration.

Prior to parametric modeling, we validated this hypothesis using the **Kolmogorov-Smirnov (K-S)** and **Shapiro-Wilk** goodness-of-fit tests on the log-transformed data $Y = \ln(\mathcal{X})$. The null hypothesis $H_0$, stating that $Y$ is normally distributed, was tested against an $\alpha = 0.05$ significance level.



While the raw data $\mathcal{X}$ exhibited extreme right-skewness, the transformed data $Y$ aligned closely with the theoretical quantiles of the standard normal distribution, justifying the use of Log-Normal parametric models.

## B. Log-Space Parametric Modeling

Standard arithmetic thresholds (e.g., $\mu - 3\sigma$) are ill-suited for heavy-tailed distributions bounded by zero, as they frequently imply physically impossible (negative) latencies. Consequently, all statistical definitions were formulated in the Log-Domain.

Let $X = \{x_1, \dots, x_n\}$ represent the set of observed iteration counts from the pilot study. We define the transformed dataset $Y = \{y_1, \dots, y_n\}$ where $y_i = \ln(x_i)$. The parameters $\mu_{log}$ and $\sigma_{log}$ are estimated using the unbiased sample mean and sample standard deviation of $Y$:

$$Y \sim \mathcal{N}(\mu_{log}, \sigma_{log})$$

$$\hat{\mu}_{log} = \frac{1}{n} \sum_{i=1}^{n} y_i$$

$$\hat{\sigma}_{log} = \sqrt{\frac{1}{n-1} \sum_{i=1}^{n} (y_i - \hat{\mu}_{log})^2}$$

## C. Oracle Threshold Definition

We define an "Oracle" trajectory as any random walk achieving a latency at least three standard deviations below the mean in the log-transformed space. This definition ensures the target is both statistically significant (representing the top $\approx 0.135\%$ of the theoretical distribution) and physically realizable ($T > 0$).

The threshold is defined in log-space as:

$$T_{log} = \hat{\mu}_{log} - 3\hat{\sigma}_{log}$$

The absolute iteration count corresponding to this threshold is given by:

$$T_{absolute} = \exp(T_{log})$$

## D. Uncertainty Estimation via Non-Parametric Bootstrapping

Standard error approximations for normal distributions (e.g., $SE \approx \sigma/\sqrt{2n}$) risk underestimating variance uncertainty in the presence of high kurtosis. To rigorously account for parameter uncertainty, we employed **Non-Parametric Bootstrapping**.

We generated $B=5000$ bootstrap samples $Y^{*(1)}, \dots, Y^{*(B)}$ by resampling from $Y$ with replacement. For each bootstrap sample $b$, the sample standard deviation $\hat{\sigma}_{log}^{*(b)}$ was calculated, yielding an empirical distribution of the variance estimator.

To construct a conservative sampling budget, we utilized the "Safety Margin" $\sigma_{safe}$, defined as the lower bound of the 95% confidence interval:

$$\sigma_{safe} = \hat{Q}_{0.025}\left(\{ \hat{\sigma}_{log}^{*(1)}, \dots, \hat{\sigma}_{log}^{*(B)} \}\right)$$

where $\hat{Q}_{0.025}$ denotes the $2.5^{th}$ percentile of the bootstrap distribution. Utilizing the lower bound for variance assumes a "thinner" tail distribution; this represents the worst-case scenario where outliers are statistically rarest, thereby maximizing the required sampling budget ($N$) to guarantee coverage.

## E. Sampling Budget Determination

The probability ($P_{safe}$) of observing a trajectory strictly better than the Oracle threshold $T_{log}$, under the conservative variance estimate, is derived from the Cumulative Distribution Function (CDF) of the normal distribution $\Phi$:

$$Z_{safe} = \frac{T_{log} - \hat{\mu}_{log}}{\sigma_{safe}}$$

$$P_{safe} = \Phi(Z_{safe})$$

Modeling the search as a sequence of Bernoulli trials, the minimum sampling budget $N$ required to find at least one Oracle trajectory with 95% confidence is given by:

$$N = \left\lceil \frac{\ln(\alpha)}{\ln(1 - P_{safe})} \right\rceil$$

Where $\alpha=0.05$ represents the acceptable risk tolerance for failing to sample an Oracle trajectory.

## F. Structural Sensitivity Analysis

To quantify the sensitivity of program convergence to processing order, we performed a **One-Sample T-Test** comparing the distribution of **log-transformed** random walk latencies ($Y$) against the log-transformed deterministic baseline established by the FIFO strategy ($\ln(L_{FIFO})$).

The T-statistic serves as a proxy for structural complexity:

* **$t \gg 0$ (High Sensitivity):** Indicates that random ordering performs significantly worse than FIFO ($p < 0.05$). This confirms that the program relies on locality or dependency chains preserved by FIFO but disrupted by random interleaving.
* **$t \ll 0$ (Optimization Opportunity):** Indicates that random ordering performs significantly better than FIFO ($p < 0.05$). This validates the existence of "Oracle" schedules superior to the standard heuristic, which the LTR model aims to learn.